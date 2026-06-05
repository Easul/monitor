#!/usr/bin/env bash
set -euo pipefail

# Rebuild EasyTier native libraries for Android 5/API 21 and copy them into this monitor app.
# Scope: this script only modifies files under the monitor repo and the EasyTier checkout passed in.
# Default EasyTier checkout: ./build/EasyTier
#
# Default builds both shipped ABIs:
#   arm64-v8a armeabi-v7a
# To build only one ABI:
#   ABIS="arm64-v8a" scripts/build_easytier_android5.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EASYTIER_DIR="${EASYTIER_DIR:-$PROJECT_ROOT/build/EasyTier}"
EASYTIER_REPO="${EASYTIER_REPO:-https://github.com/Easul/EasyTier.git}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-21}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
BUILD_APK="${BUILD_APK:-1}"

rust_target_for_abi() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    *)
      echo "Unsupported ABI: $1" >&2
      echo "Supported: arm64-v8a, armeabi-v7a" >&2
      exit 1
      ;;
  esac
}

JNI_DIR="$EASYTIER_DIR/easytier-contrib/easytier-android-jni"
FFI_DIR="$EASYTIER_DIR/easytier-contrib/easytier-ffi"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

require_file() {
  [ -f "$1" ] || {
    echo "Missing file: $1" >&2
    exit 1
  }
}

require_dir() {
  [ -d "$1" ] || {
    echo "Missing directory: $1" >&2
    exit 1
  }
}

require_cmd cargo
require_cmd rustup
require_cmd readelf
require_cmd python3
if [ ! -d "$EASYTIER_DIR" ]; then
  require_cmd git
  echo "==> EasyTier checkout not found; cloning $EASYTIER_REPO into $EASYTIER_DIR"
  mkdir -p "$(dirname "$EASYTIER_DIR")"
  git clone "$EASYTIER_REPO" "$EASYTIER_DIR"
fi
require_dir "$JNI_DIR"
require_dir "$FFI_DIR"

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "Missing cargo-ndk. Install with: cargo install cargo-ndk" >&2
  exit 1
fi

for ABI in $ABIS; do
  rustup target add "$(rust_target_for_abi "$ABI")" >/dev/null
done

patch_easytier_sources() {
  echo "==> Patching EasyTier sources for Android 5"
  python3 - "$EASYTIER_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])

def read(path):
    return path.read_text(encoding="utf-8")

def write(path, text):
    path.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"patch anchor not found: {label}")
    return text.replace(old, new, 1)

# 1. Avoid Android 5 missing getifaddrs/freeifaddrs by using rtnetlink for address enumeration.
netlink = root / "easytier/src/common/ifcfg/netlink.rs"
text = read(netlink)
text = text.replace("    ifaddrs::getifaddrs,\n", "")
text = text.replace("    sys::socket::SockaddrLike as _,\n", "")
text = text.replace("use pnet::ipnetwork::ip_mask_to_prefix;\n", "")
old_list = '''    pub fn list_addresses(name: &str) -> Result<Vec<IpInet>, Error> {
        let mut result = vec![];

        for interface in getifaddrs()
            .with_context(|| "failed to call getifaddrs")?
            .filter(|x| x.interface_name == name)
        {
            let (Some(address), Some(netmask)) = (interface.address, interface.netmask) else {
                continue;
            };

            use nix::sys::socket::AddressFamily::{Inet, Inet6};

            let (address, netmask) = match (address.family(), netmask.family()) {
                (Some(Inet), Some(Inet)) => (
                    IpAddr::V4(address.as_sockaddr_in().unwrap().ip()),
                    IpAddr::V4(netmask.as_sockaddr_in().unwrap().ip()),
                ),
                (Some(Inet6), Some(Inet6)) => (
                    IpAddr::V6(address.as_sockaddr_in6().unwrap().ip()),
                    IpAddr::V6(netmask.as_sockaddr_in6().unwrap().ip()),
                ),
                (_, _) => continue,
            };

            let prefix = ip_mask_to_prefix(netmask).unwrap();

            result.push(IpInet::new(address, prefix).unwrap());
        }
        Ok(result)
    }
'''
new_list = '''    pub fn list_addresses(name: &str) -> Result<Vec<IpInet>, Error> {
        let mut result = vec![];
        let ifindex = Self::get_interface_index(name)?;

        for message in Self::list_address_messages(AddressFamily::Inet)?
            .into_iter()
            .chain(Self::list_address_messages(AddressFamily::Inet6)?)
            .filter(|message| message.header.index == ifindex)
        {
            let address = message.attributes.iter().find_map(|attribute| match attribute {
                AddressAttribute::Local(address) | AddressAttribute::Address(address) => {
                    Some(*address)
                }
                _ => None,
            });

            if let Some(address) = address {
                result.push(IpInet::new(address, message.header.prefix_len).unwrap());
            }
        }
        Ok(result)
    }

    fn list_address_messages(address_family: AddressFamily) -> Result<Vec<AddressMessage>, Error> {
        let mut message = AddressMessage::default();
        message.header.family = address_family;

        let s = send_netlink_req(
            RouteNetlinkMessage::GetAddress(message),
            NLM_F_REQUEST | NLM_F_DUMP,
        )?;

        let mut ret_vec = vec![];
        let mut resp = Vec::<u8>::new();
        loop {
            if resp.is_empty() {
                let (new_resp, _) = s.recv_from_full()?;
                resp = new_resp;
            }
            let ret = NetlinkMessage::<RouteNetlinkMessage>::deserialize(&resp)
                .with_context(|| "Failed to deserialize netlink address message")?;
            resp = resp.split_off(ret.buffer_len());

            tracing::debug!("net link address response <<< {:?}", ret);

            match ret.payload {
                NetlinkPayload::Error(e) => {
                    if e.code == NonZero::new(0) {
                        continue;
                    } else {
                        return Err(e.to_io().into());
                    }
                }
                NetlinkPayload::InnerMessage(RouteNetlinkMessage::NewAddress(m)) => {
                    ret_vec.push(m);
                }
                NetlinkPayload::Done(_) => {
                    break;
                }
                p => {
                    tracing::error!("Unexpected netlink address response: {:?}", p);
                    return Err(anyhow::anyhow!("Unexpected netlink address response").into());
                }
            }
        }

        Ok(ret_vec)
    }
'''
text = replace_once(text, old_list, new_list, "netlink list_addresses getifaddrs replacement")
write(netlink, text)

# 2. Avoid linking pnet_datalink getifaddrs path on Android. EasyTier can still use STUN/UDP local IP paths.
network = root / "easytier/src/common/network.rs"
text = read(network)
old = '''        #[cfg(not(target_os = "windows"))]
        let ifaces = pnet::datalink::interfaces();'''
new = '''        #[cfg(target_os = "android")]
        let ifaces: Vec<NetworkInterface> = Vec::new();
        #[cfg(not(any(target_os = "android", target_os = "windows")))]
        let ifaces = pnet::datalink::interfaces();'''
text = replace_once(text, old, new, "Android pnet interface enumeration")
write(network, text)

# 3. Disable Android fake-TCP netfilter fallback that pulls pnet packet interfaces into the shared object.
netfilter = root / "easytier/src/tunnel/fake_tcp/netfilter/mod.rs"
text = read(netfilter)
text = replace_once(text, "pub mod pnet;", "#[cfg(not(target_os = \"android\"))]\npub mod pnet;", "cfg pnet module")
old = '''    _ => {
        pub fn create_tun(
            interface_name: &str,
            src_addr: Option<SocketAddr>,
            dst_addr: SocketAddr,
        ) -> io::Result<Arc<dyn super::stack::Tun>> {
            Ok(Arc::new(pnet::PnetTun::new(
                interface_name,
                pnet::create_packet_filter(src_addr, dst_addr),
            )?))
        }
    }'''
new = '''    target_os = "android" => {
        pub fn create_tun(
            _interface_name: &str,
            _src_addr: Option<SocketAddr>,
            _dst_addr: SocketAddr,
        ) -> io::Result<Arc<dyn super::stack::Tun>> {
            Err(io::Error::other("fake TCP netfilter is not supported on Android"))
        }
    }

    _ => {
        pub fn create_tun(
            interface_name: &str,
            src_addr: Option<SocketAddr>,
            dst_addr: SocketAddr,
        ) -> io::Result<Arc<dyn super::stack::Tun>> {
            Ok(Arc::new(pnet::PnetTun::new(
                interface_name,
                pnet::create_packet_filter(src_addr, dst_addr),
            )?))
        }
    }'''
text = replace_once(text, old, new, "Android fake TCP netfilter branch")
write(netfilter, text)

# 4. Patch network-interface only for this EasyTier checkout so Android does not compile getifaddrs backend.
patch_dir = root / "patches/network-interface-2.0.5"
if not patch_dir.exists():
    registry_roots = list(Path.home().glob(".cargo/registry/src/*/network-interface-2.0.5"))
    if not registry_roots:
        raise SystemExit("network-interface-2.0.5 not found in cargo registry; run cargo fetch in EasyTier first")
    shutil.copytree(registry_roots[0], patch_dir)

mod_rs = patch_dir / "src/target/mod.rs"
text = read(mod_rs)
text = text.replace('#[cfg(any(target_os = "android", target_os = "linux"))]\nmod linux;', '#[cfg(target_os = "linux")]\nmod linux;')
text = text.replace('#[cfg(any(target_os = "android", target_os = "linux"))]\npub use linux::*;', '#[cfg(target_os = "linux")]\npub use linux::*;')
if '#[cfg(target_os = "android")]\nmod android;' not in text:
    text = text.replace('#[cfg(target_os = "linux")]\npub use linux::*;\n', '#[cfg(target_os = "linux")]\npub use linux::*;\n\n#[allow(unused_imports)]\n#[cfg(target_os = "android")]\nmod android;\n\n#[allow(unused_imports)]\n#[cfg(target_os = "android")]\npub use android::*;\n')
text = text.replace('#[cfg(not(target_os = "windows"))]\nmod getifaddrs;', '#[cfg(not(any(target_os = "android", target_os = "windows")))]\nmod getifaddrs;')
text = text.replace('#[cfg(not(target_os = "windows"))]\npub use getifaddrs::*;', '#[cfg(not(any(target_os = "android", target_os = "windows")))]\npub use getifaddrs::*;')
write(mod_rs, text)
write(patch_dir / "src/target/android.rs", '''use crate::{NetworkInterface, NetworkInterfaceConfig, Result};

impl NetworkInterfaceConfig for NetworkInterface {
    fn show() -> Result<Vec<NetworkInterface>> {
        Ok(Vec::new())
    }
}
''')

cargo = root / "Cargo.toml"
text = read(cargo)
patch = '[patch.crates-io]\nnetwork-interface = { path = "patches/network-interface-2.0.5" }\n'
if 'network-interface = { path = "patches/network-interface-2.0.5" }' not in text:
    if '[patch.crates-io]' in text:
        text = text.replace('[patch.crates-io]\n', '[patch.crates-io]\nnetwork-interface = { path = "patches/network-interface-2.0.5" }\n', 1)
    else:
        text = text.rstrip() + '\n\n' + patch
    write(cargo, text)

# 5. Make JNI library carry DT_NEEDED: libeasytier_ffi.so.
build_rs = root / "easytier-contrib/easytier-android-jni/build.rs"
write(build_rs, '''use std::path::PathBuf;

fn main() {
    println!("cargo:rustc-link-lib=dylib=easytier_ffi");

    let target = std::env::var("TARGET").unwrap_or_default();
    let profile = std::env::var("PROFILE").unwrap_or_else(|_| "release".to_string());
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let repo_root = manifest_dir.parent().and_then(|p| p.parent()).unwrap();
    let lib_path = repo_root.join("target").join(target).join(profile);

    println!("cargo:rustc-link-search=native={}", lib_path.display());
    println!("cargo:rerun-if-changed=build.rs");
}
''')
lib_rs = root / "easytier-contrib/easytier-android-jni/src/lib.rs"
text = read(lib_rs)
attr = '#[link(name = "easytier_ffi", kind = "dylib")]\n'
if attr.strip() not in text:
    text = replace_once(text, 'unsafe extern "C" {', attr + 'unsafe extern "C" {', "JNI extern link attribute")
    write(lib_rs, text)
PY
}

patch_monitor_loader() {
  echo "==> Patching monitor JNI load order"
  local kt="$PROJECT_ROOT/app/src/main/java/com/easytier/jni/EasyTierJNI.kt"
  require_file "$kt"
  python3 - "$kt" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = 'System.loadLibrary("easytier_android_jni")'
new = 'System.loadLibrary("easytier_ffi"); System.loadLibrary("easytier_android_jni")'
if new not in text:
    if old not in text:
        raise SystemExit("EasyTierJNI loadLibrary anchor not found")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
PY
}

library_paths_for_abi() {
  local abi="$1"
  local rust_target
  rust_target="$(rust_target_for_abi "$abi")"
  JNI_LIB="$EASYTIER_DIR/target/$rust_target/release/libeasytier_android_jni.so"
  FFI_LIB="$EASYTIER_DIR/target/$rust_target/release/libeasytier_ffi.so"
  DEST_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$abi"
}

build_libraries() {
  local abi="$1"
  echo "==> Building EasyTier FFI for $abi / API $ANDROID_PLATFORM"
  (cd "$FFI_DIR" && cargo ndk -t "$abi" --platform "$ANDROID_PLATFORM" build --release)

  echo "==> Building EasyTier Android JNI for $abi / API $ANDROID_PLATFORM"
  (cd "$JNI_DIR" && cargo ndk -t "$abi" --platform "$ANDROID_PLATFORM" build --release)
}

verify_libraries() {
  local abi="$1"
  library_paths_for_abi "$abi"
  echo "==> Verifying native libraries for $abi"
  require_file "$FFI_LIB"
  require_file "$JNI_LIB"

  if readelf -Ws "$FFI_LIB" | grep -E ' (getifaddrs|freeifaddrs)$' >/dev/null; then
    echo "FFI library still references getifaddrs/freeifaddrs; Android 5 will fail to load it." >&2
    readelf -Ws "$FFI_LIB" | grep -E ' (getifaddrs|freeifaddrs)$' >&2
    exit 1
  fi

  if ! readelf -d "$JNI_LIB" | grep 'libeasytier_ffi.so' >/dev/null; then
    echo "JNI library does not carry DT_NEEDED: libeasytier_ffi.so" >&2
    readelf -d "$JNI_LIB" | grep NEEDED >&2 || true
    exit 1
  fi

  echo "Verified: no getifaddrs/freeifaddrs import and JNI has DT_NEEDED libeasytier_ffi.so"
}

copy_libraries() {
  local abi="$1"
  library_paths_for_abi "$abi"
  echo "==> Copying libraries into $DEST_DIR"
  mkdir -p "$DEST_DIR"
  cp "$FFI_LIB" "$DEST_DIR/"
  cp "$JNI_LIB" "$DEST_DIR/"
}

build_apk() {
  if [ "$BUILD_APK" = "1" ]; then
    echo "==> Building monitor debug APK"
    (cd "$PROJECT_ROOT" && ./gradlew :app:assembleDebug)
    echo "APK: $PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  fi
}

patch_easytier_sources
patch_monitor_loader
for ABI in $ABIS; do
  build_libraries "$ABI"
  verify_libraries "$ABI"
  copy_libraries "$ABI"
done
build_apk
