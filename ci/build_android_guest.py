#!/usr/bin/env python3
"""
Builds the PocketVM Android 9 (arm64) guest image:

  1. Linux 5.15.x (arm64) kernel with Android binder + ashmem built in
  2. Minimal Debian 12 rootfs (debootstrap, systemd, DHCP network)
  3. redroid Android 9 arm64 rootfs extracted from the public Docker image
     (Apache-2.0 AOSP userspace) under /android, started by a systemd unit
     inside a PID namespace (Android init as PID 1 of its own namespace)
  4. Packed as ext4 image (gzip) + kernel in a zip, published as a GitHub release

The app downloads this bundle, boots it under the bundled QEMU (TCG), and
connects to the guest via adb (slirp hostfwd) + scrcpy for display/input.
"""
import gzip
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile

WORK = "/mnt/pocketvm-guest"
KERNEL_URL_BASE = "https://cdn.kernel.org/pub/linux/kernel/v5.x/"
REDROID_REPO = "redroid/redroid"
REDROID_TAG = "9.0.0-latest"
OUT_DIR = os.environ.get("GUEST_OUT", "out-guest")

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
GITHUB_REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "novaxmyth/pocketvm")


def sh(cmd, **kw):
    print(f"+ {cmd}", flush=True)
    subprocess.run(cmd, shell=True, check=True, **kw)


def http_get(url, dest=None, headers=None, retries=4):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers or {"User-Agent": "pocketvm-ci"})
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            if dest:
                with open(dest, "wb") as f:
                    f.write(data)
            return data
        except Exception as e:
            last = e
            print(f"retry {attempt + 1} for {url}: {e}", flush=True)
            time.sleep(5)
    raise RuntimeError(f"download failed: {url}: {last}")


# ---------------------------------------------------------------- kernel

def latest_515():
    listing = http_get(KERNEL_URL_BASE).decode()
    vers = [int(m) for m in re.findall(r"linux-5\.15\.(\d+)\.tar\.xz", listing)]
    return max(vers)


def build_kernel(work):
    patch = latest_515()
    kver = f"5.15.{patch}"
    kdir = os.path.join(work, f"linux-{kver}")
    image = os.path.join(kdir, "arch/arm64/boot/Image")
    if os.path.exists(image):
        print(f"kernel {kver} already built (cached)")
        return kver, image

    tar = os.path.join(work, f"linux-{kver}.tar.xz")
    if not os.path.exists(tar):
        print(f"downloading kernel {kver}…", flush=True)
        http_get(KERNEL_URL_BASE + f"linux-{kver}.tar.xz", dest=tar)
    if not os.path.isdir(kdir):
        sh(f"tar -xf {tar} -C {work}")

    env = dict(os.environ, ARCH="arm64", CROSS_COMPILE="aarch64-linux-gnu-")
    def mk(*args):
        subprocess.run(["make", "-C", kdir, *args], env=env, check=True)

    mk("defconfig")
    cfg = [
        "-e", "ANDROID", "-e", "ANDROID_BINDER_IPC", "-d", "ANDROID_BINDERFS",
        "-e", "ASHMEM", "-e", "SECURITY_SELINUX", "-e", "SECURITY_SELINUX_DEVELOP",
        "-e", "VIRTIO", "-e", "VIRTIO_PCI", "-e", "VIRTIO_BLK", "-e", "VIRTIO_NET",
        "-e", "VIRTIO_MMIO", "-e", "EXT4_FS", "-e", "PCI", "-e", "TMPFS",
        "-e", "SERIAL_AMBA_PL011", "-e", "SERIAL_AMBA_PL011_CONSOLE",
        "--set-str", "ANDROID_BINDER_DEVICES", "binder,hwbinder,vndbinder",
    ]
    subprocess.run(["./scripts/config", *cfg], cwd=kdir, check=True)
    mk("olddefconfig")
    print("building kernel (this takes a while)…", flush=True)
    mk("-j", str(os.cpu_count()), "Image", "modules")
    sh(f"make -C {kdir} ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- "
       f"INSTALL_MOD_PATH={work}/mods modules_install")
    return kver, image


# ---------------------------------------------------------------- rootfs

def debootstrap(work):
    root = os.path.join(work, "rootfs")
    if os.path.isdir(os.path.join(root, "bin")):
        print("rootfs already bootstrapped")
        return root
    keyring = "/usr/share/keyrings/debian-archive-keyring.gpg"
    kr = f"--keyring={keyring} " if os.path.exists(keyring) else "--no-check-gpg "
    sh(f"sudo debootstrap --arch=arm64 --variant=minbase --include=systemd-sysv "
       f"{kr}bookworm {root} http://deb.debian.org/debian")
    return root


def configure_rootfs(root, kver, mods_dir):
    def w(rel, content, mode=None):
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(content)
        if mode:
            os.chmod(p, mode)

    w("etc/fstab", "/dev/vda1  /  ext4  errors=remount-ro  0 1\n")
    w("etc/hostname", "pocketvm\n")
    # Slirp DNS
    resolv = os.path.join(root, "etc/resolv.conf")
    if os.path.islink(resolv) or os.path.exists(resolv):
        os.remove(resolv)
    w("etc/resolv.conf", "nameserver 10.0.2.3\noptions timeout:1 attempts:2\n")

    w("etc/systemd/network/80-vm.network",
      "[Match]\nName=en* eth*\n\n[Network]\nDHCP=yes\n")

    # enable networkd
    wants = os.path.join(root, "etc/systemd/system/multi-user.target.wants")
    os.makedirs(wants, exist_ok=True)
    for unit in ("systemd-networkd.service",):
        dst = os.path.join(wants, unit)
        if not os.path.exists(dst):
            os.symlink(f"/lib/systemd/system/{unit}", dst)

    w("usr/local/bin/start-android.sh",
      "#!/bin/sh\n"
      "# Android (redroid) inside a PID namespace: /init must be PID 1 of its ns.\n"
      "cd /android\n"
      "exec unshare --pid --fork --mount-proc /init \\\n"
      "  androidboot.redroid_width=720 androidboot.redroid_height=1280 \\\n"
      "  androidboot.redroid_dpi=160 androidboot.redroid_gpu_mode=guest \\\n"
      "  androidboot.selinux=permissive\n",
      mode=0o755)

    w("etc/systemd/system/android.service",
      "[Unit]\n"
      "Description=Android 9 guest (redroid)\n"
      "After=local-fs.target\n"
      "\n"
      "[Service]\n"
      "Type=simple\n"
      "ExecStart=/usr/local/bin/start-android.sh\n"
      "Restart=on-failure\n"
      "KillMode=none\n"
      "\n"
      "[Install]\n"
      "WantedBy=multi-user.target\n")
    dst = os.path.join(wants, "android.service")
    if not os.path.exists(dst):
        os.symlink("/etc/systemd/system/android.service", dst)

    # kernel modules
    mdest = os.path.join(root, "lib/modules")
    os.makedirs(mdest, exist_ok=True)
    src = os.path.join(mods_dir, "lib/modules", kver)
    if os.path.isdir(src):
        shutil.copytree(src, os.path.join(mdest, kver), dirs_exist_ok=True)
        sh(f"depmod -b {root} {kver}")

    # Android root
    os.makedirs(os.path.join(root, "android/data/local/tmp"), exist_ok=True)


# ---------------------------------------------------------------- redroid

def redroid_pull(work, root):
    android = os.path.join(root, "android")
    # Guard against a stale cached userspace from a previous Android version
    # (the CI cache keeps /mnt/pocketvm-guest across runs): if the extracted
    # tree was built from a different redroid tag, wipe it and re-pull.
    tagfile = os.path.join(android, ".redroid-tag")
    if os.path.isfile(tagfile):
        with open(tagfile) as f:
            cached_tag = f.read().strip()
        if cached_tag != REDROID_TAG:
            print(f"stale redroid userspace ({cached_tag}) — wiping for {REDROID_TAG}", flush=True)
            shutil.rmtree(android, ignore_errors=True)
    if os.path.isdir(os.path.join(android, "system")):
        print("redroid already extracted")
        return
    token = json.loads(http_get(
        f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{REDROID_REPO}:pull"
    ))["token"]
    H = {"Authorization": f"Bearer {token}",
         "Accept": ",".join([
             "application/vnd.oci.image.index.v1+json",
             "application/vnd.oci.image.manifest.v1+json",
             "application/vnd.docker.distribution.manifest.list.v2+json",
             "application/vnd.docker.distribution.manifest.v2+json"])}

    index = json.loads(http_get(
        f"https://registry-1.docker.io/v2/{REDROID_REPO}/manifests/{REDROID_TAG}", headers=H))
    digest = None
    for m in index.get("manifests", []):
        p = m.get("platform", {})
        if p.get("architecture") == "arm64" and p.get("os") == "linux":
            digest = m["digest"]
    if not digest:
        raise RuntimeError("no arm64 manifest for redroid")

    manifest = json.loads(http_get(
        f"https://registry-1.docker.io/v2/{REDROID_REPO}/manifests/{digest}", headers=H))
    blobs = os.path.join(work, "blobs")
    os.makedirs(blobs, exist_ok=True)
    total = len(manifest["layers"])
    for i, layer in enumerate(manifest["layers"], 1):
        d = layer["digest"]
        blob = os.path.join(blobs, d.replace(":", "_"))
        if not os.path.exists(blob):
            print(f"layer {i}/{total}: {d[:19]}…", flush=True)
            http_get(f"https://registry-1.docker.io/v2/{REDROID_REPO}/blobs/{d}", dest=blob, headers=H)
        extract_layer(blob, android)
    with open(os.path.join(android, ".redroid-tag"), "w") as f:
        f.write(REDROID_TAG + "\n")
    print("redroid rootfs ready")


def extract_layer(blob, dest):
    os.makedirs(dest, exist_ok=True)
    with tarfile.open(blob, "r:*") as tf:
        for member in tf:
            name = member.name.lstrip("./")
            if not name:
                continue
            base = os.path.basename(name)
            parent = os.path.dirname(os.path.join(dest, name))
            if base == ".wh..wh..opq":
                if os.path.isdir(parent):
                    for e in os.listdir(parent):
                        os.remove(os.path.join(parent, e)) if os.path.isfile(os.path.join(parent, e)) \
                            else shutil.rmtree(os.path.join(parent, e))
                continue
            if base.startswith(".wh."):
                target = os.path.join(parent, base[4:])
                if os.path.lexists(target):
                    if os.path.isdir(target) and not os.path.islink(target):
                        shutil.rmtree(target)
                    else:
                        os.remove(target)
                continue
            # keep tar modes (exec bits) — script runs as root so chown works
            tf.extract(member, dest)


# ---------------------------------------------------------------- pack

def pack(work, kver, image):
    root = os.path.join(work, "rootfs")
    du = subprocess.run(["du", "-sk", root], capture_output=True, text=True).stdout
    used_kb = int(du.split()[0])
    size_kb = max(3 * 1024 * 1024, int(used_kb * 1.30))
    img = os.path.join(work, "rootfs.img")
    if os.path.exists(img):
        os.remove(img)
    print(f"creating ext4 image ({size_kb // 1024} MB)…", flush=True)
    sh(f"mke2fs -q -t ext4 -b 1024 -d {root} -F {img} {size_kb}")

    os.makedirs(OUT_DIR, exist_ok=True)
    gz_path = os.path.join(OUT_DIR, "rootfs.img.gz")
    print("gzip rootfs image…", flush=True)
    with open(img, "rb") as src, gzip.GzipFile(gz_path, "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst, length=1 << 20)

    zip_path = os.path.join(OUT_DIR, f"pocketvm-android9-arm64-v{os.environ.get('GITHUB_RUN_NUMBER', '0')}.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
        z.write(gz_path, "rootfs.img.gz")
        z.write(image, "vmlinuz")
        z.writestr("manifest.json", json.dumps({
            "name": "PocketVM Android 9 guest (arm64)",
            "android": "9 (redroid userspace)",
            "kernel": kver,
            "created": time.strftime("%Y-%m-%d"),
        }, indent=2))
    print(f"bundle: {zip_path} ({os.path.getsize(zip_path) / 1e9:.2f} GB)")
    return zip_path


def publish(zip_path):
    if not GITHUB_TOKEN:
        print("no GITHUB_TOKEN — skipping release publish")
        return
    import urllib.error
    api = f"https://api.github.com/repos/{GITHUB_REPOSITORY}"
    H = {"Authorization": f"Bearer {GITHUB_TOKEN}",
         "Accept": "application/vnd.github+json",
         "User-Agent": "pocketvm-ci"}
    tag = f"android9-v{os.environ.get('GITHUB_RUN_NUMBER', '0')}"
    body = json.dumps({
        "tag_name": tag,
        "name": f"Android 9 guest image ({tag})",
        "body": "Prebuilt Android 9 (redroid) arm64 guest for PocketVM: rootfs.ext4 (gz) + Linux kernel. "
                "Import from the app: New VM → Download Android 9 guest image.",
        "prerelease": False,
    }).encode()
    req = urllib.request.Request(f"{api}/releases", data=body, headers=H, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            rel = json.loads(r.read())
    except urllib.error.HTTPError as e:
        print(f"release create failed: {e.read().decode()}")
        raise
    upload = rel["upload_url"].split("{")[0]
    aname = os.path.basename(zip_path)
    print(f"uploading {aname} ({os.path.getsize(zip_path) / 1e9:.2f} GB)…", flush=True)
    data = open(zip_path, "rb")
    req = urllib.request.Request(
        f"{upload}?name={aname}", data=data,
        headers={**H, "Content-Type": "application/zip"}, method="POST")
    with urllib.request.urlopen(req, timeout=7200) as r:
        print("uploaded:", json.loads(r.read())["browser_download_url"])


def main():
    os.makedirs(WORK, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)

    kver, image = build_kernel(WORK)
    root = debootstrap(WORK)
    configure_rootfs(root, kver, WORK)
    redroid_pull(WORK, root)
    pack(WORK, kver, image)
    # Publishing is done by the workflow's `gh release` step — uploads via
    # urllib kept dying on flaky TLS mid-transfer and failing the whole job.
    print("DONE")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        raise
