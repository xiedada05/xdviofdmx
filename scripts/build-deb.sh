#!/usr/bin/env bash
# build-deb.sh —— 构建 xdviofdmx 的 Debian 包（轻量 dpkg-deb，不需要 debhelper）。
#
# Java 依赖采用混合模式（详见 README「Debian 打包」）：
#   - jar 用 mvn package -Pdeb 构建：剔除 Debian 已打包的 13 个库
#     （batik/bcpkix/bcprov/commons-* /dom4j/jaxen/slf4j/twelvemonkeys/
#     xml-apis/xmlgraphics/zip4j）；
#   - 运行时由启动脚本读取本包生成的 /usr/share/xdviofdmx/classpath，
#     挂接系统的 /usr/share/java 库（Depends 相应 lib*-java 包）；
#   - ofdrw、xdvjni、pdfbox 3.x、jbig2-imageio、ujmp-core 等 Debian
#     未提供的库仍打包在 jar 内。
#
# 用法:
#   scripts/build-deb.sh [选项]
# 选项:
#   --jar JAR       复用现成 jar（须为 -Pdeb 产物，脚本会校验）；缺省自动构建
#   --arch ARCH     覆盖架构（缺省按 jar 内本地库嗅探：三平台齐全→all，否则 amd64）
#   --revision N    Debian 修订号（缺省 1，版本写成 <pom版本>-<修订>）
#   --out DIR       输出目录（缺省 target/）
#
# 产物: target/xdviofdmx_<版本>-<修订>_<架构>.deb

set -euo pipefail

die() { echo "build-deb: $*" >&2; exit 2; }

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)

jar_arg='' arch='' revision=1 out_dir="$root/target"
while [[ $# -gt 0 ]]; do
  case $1 in
    --jar) jar_arg=$2; shift 2 ;;
    --arch) arch=$2; shift 2 ;;
    --revision) revision=$2; shift 2 ;;
    --out) out_dir=$2; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数: $1（--help 查看用法）" ;;
  esac
done

command -v mvn >/dev/null 2>&1 || die "找不到 mvn"
command -v dpkg-deb >/dev/null 2>&1 || die "找不到 dpkg-deb"
command -v unzip >/dev/null 2>&1 || die "找不到 unzip"

# ---- 版本（pom.xml -> <版本>-<修订>）----
version=$(mvn -B -q --no-transfer-progress -Dexpression=project.version \
  -DforceStdout help:evaluate) || die "读取 pom 版本失败"
[[ $version == *-SNAPSHOT ]] && die "版本 $version 为 SNAPSHOT，请先发布正式版本"
deb_version="${version}-${revision}"

maintainer=${XDVIOFDMX_MAINTAINER:-'Xie Youtian <xiedada05@users.noreply.github.com>'}
homepage=https://github.com/xiedada05/xdviofdmx

# ---- jar（缺省自动 -Pdeb 构建；复用时校验确为剔除版）----
if [[ -n $jar_arg ]]; then
  [[ -f $jar_arg ]] || die "jar 不存在: $jar_arg"
  jar=$(cd -- "$(dirname -- "$jar_arg")" && readlink -f -- "$(basename -- "$jar_arg")")
else
  jar="$root/target/xdviofdmx-${version}-deb.jar"
  [[ -f $jar ]] || mvn -B --no-transfer-progress -DskipTests -Pdeb package \
    || die "mvn package -Pdeb 失败"
fi
if unzip -l "$jar" 2>/dev/null | grep -q 'org/bouncycastle/'; then
  die "jar 内含 Debian 已提供的库（如 bouncycastle）。
  请用 mvn -B -DskipTests -Pdeb package 重新构建，
  或通过 --jar 指向 xdviofdmx-<版本>-deb.jar。"
fi

# ---- 架构嗅探：jar 内本地库决定 all / amd64 ----
if [[ -z $arch ]]; then
  arch=amd64
  n_so=$(unzip -l "$jar" | grep -c 'xdvjni/native/libxdvjni\.so$' || true)
  n_dylib=$(unzip -l "$jar" | grep -c 'xdvjni/native/libxdvjni\.dylib$' || true)
  n_dll=$(unzip -l "$jar" | grep -c 'xdvjni/native/xdvjni\.dll$' || true)
  if (( n_so && n_dylib && n_dll )); then
    arch=all
  elif (( ! n_so )); then
    die "jar 内不含 libxdvjni.so（Linux 本地库），无法组成 Debian 包。"
  fi
fi

# ---- Debian 系统库 -> classpath ----
# 13 个可替换坐标对应的 lib*-java 包；jar 文件名在构建时解析
# （已安装用 dpkg -L；未安装用 apt-get download + dpkg-deb -c，无需 root）。
system_pkgs=(
  libbatik-java
  libbcprov-java
  libbcpkix-java
  libcommons-compress-java
  libcommons-io-java
  libcommons-logging-java
  libdom4j-java
  libjaxen-java
  libslf4j-java
  libtwelvemonkeys-java
  libxml-commons-external-java
  libxmlgraphics-commons-java
  libzip4j-java
)

pkg_jar_paths() {
  local pkg=$1 tmp f
  if dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
    while IFS= read -r p; do
      [[ -f $p && ! -L $p ]] && printf '%s\n' "$p"
    done < <(dpkg -L "$pkg" | grep '^/usr/share/java/[^/]*\.jar$')
    return
  fi
  # 未安装：下载包并直接读文件清单（不落系统）
  tmp=$(mktemp -d)
  if ! ( cd -- "$tmp" && apt-get download --no-install-recommends "$pkg" ) \
       >/dev/null 2>&1; then
    rm -rf -- "$tmp"
    die "解析未安装包 $pkg 失败：apt-get download 不可用。
  请先 sudo apt install ${system_pkgs[*]} 后重试。"
  fi
  f=$(ls "$tmp/${pkg}"_*.deb 2>/dev/null | head -1) || true
  [[ -n $f ]] || { rm -rf -- "$tmp"; die "包 $pkg 下载结果异常"; }
  # $1 为权限位：l 开头是符号链接（版本别名），只留真实文件
  dpkg-deb -c "$f" | awk '$1 !~ /^l/ {print $NF}' \
    | sed -n 's#^\./\(usr/share/java/[^/]*\.jar\)$#/\1#p'
  rm -rf -- "$tmp"
}

# libslf4j-java 附带全部 binding/桥接 jar；classpath 上出现多个 binding 会被
# slf4j 随机择一（nop 会吞日志）。与 dist jar 一致：只保留 slf4j-api。
filter_bindings() {
  grep -vE '/(slf4j-(jcl|jdk14|log4j12|migrator|nop|reload4j|simple)|jcl-over-slf4j|jul-to-slf4j|log4j-over-slf4j)(-[0-9][0-9A-Za-z.-]*)?\.jar$' || true
}

classpath=''
for p in "${system_pkgs[@]}"; do
  mapfile -t jars < <(pkg_jar_paths "$p" | filter_bindings)
  ((${#jars[@]})) || die "$p 在 /usr/share/java 下没有 jar（包清单异常？）"
  for j in "${jars[@]}"; do classpath+="$j:"; done
done
classpath=${classpath%:}

# ---- 组装暂存树 ----
stage="$root/target/deb-staging"
rm -rf -- "$stage"
install -d -m755 \
  "$stage/DEBIAN" \
  "$stage/usr/bin" \
  "$stage/usr/share/xdviofdmx" \
  "$stage/usr/share/doc/xdviofdmx" \
  "$stage/usr/share/man/man1"

install -m755 "$root/xdviofdmx" "$stage/usr/bin/xdviofdmx"
install -m644 "$jar" "$stage/usr/share/xdviofdmx/xdviofdmx.jar"
printf '%s\n' "$classpath" >"$stage/usr/share/xdviofdmx/classpath"
chmod 644 "$stage/usr/share/xdviofdmx/classpath"
install -m644 "$root/README.md" "$stage/usr/share/doc/xdviofdmx/README.md"

gzip -9n -c "$root/scripts/xdviofdmx.1" \
  >"$stage/usr/share/man/man1/xdviofdmx.1.gz"

# copyright（机器可读 DEP-5；jar 内捆绑组件一并列出）
cat >"$stage/usr/share/doc/xdviofdmx/copyright" <<EOF
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: xdviofdmx
Source: $homepage

Files: *
Copyright: 2026 xdviofdmx authors
License: MIT
 [MIT 全文见下方 License 段；与仓库 LICENSE 一致。]

Files: usr/share/xdviofdmx/xdviofdmx.jar
Copyright: 2026 xdviofdmx authors 及下列捆绑库的作者
License: MIT and Apache-2.0 and LGPL-3
 jar 内捆绑（Debian 未提供，见 README「Debian 打包」）：
   - ofdrw 全模块 2.5.1              MIT
   - xdvjii（tectonic_xdv JNI 绑定）  MIT
   - pdfbox / pdfbox-io / fontbox 3.0.8   Apache-2.0
   - jbig2-imageio 3.0.5             Apache-2.0
   - ujmp-core 0.3.0                 LGPL-3.0（jar 可整体替换，符合 LGPL 豁免）

License: MIT
Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:
.
The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.
.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

License: Apache-2.0
 On Debian systems, the complete text of the Apache License Version 2.0
 is available in /usr/share/common-licenses/Apache-2.0.

License: LGPL-3
 On Debian systems, the complete text of the GNU Lesser General Public
 License Version 3 is available in /usr/share/common-licenses/LGPL-3.
EOF

# 最小 Debian changelog（policy 要求随包附带上游变更记录）
cat >"$stage/usr/share/doc/xdviofdmx/changelog" <<EOF
xdviofdmx ($deb_version) unstable; urgency=medium

  * Debian package of xdviofdmx $version.

 -- $maintainer  $(date -R)
EOF
gzip -9n -c "$stage/usr/share/doc/xdviofdmx/changelog" \
  >"$stage/usr/share/doc/xdviofdmx/changelog.gz"
rm -- "$stage/usr/share/doc/xdviofdmx/changelog"
# 重定向/heredoc 产物按 umask 可能成 664，统一 644
chmod 644 "$stage/usr/share/man/man1/xdviofdmx.1.gz" \
  "$stage/usr/share/doc/xdviofdmx/copyright" \
  "$stage/usr/share/doc/xdviofdmx/changelog.gz"

# ---- DEBIAN/control ----
# JRE 依赖沿用 Debian 官方 Java 包（jetty12/tomcat11）的规范写法：
# 注意版本是 "2:1.17" 而非 "2:17"——default-jre-headless 的版本号带 "1." 前缀，
# 写成 >= 2:17 时 dpkg 比较 1.xx < 17，约束永远无法满足
depends="default-jre-headless (>= 2:1.17) | java17-runtime-headless"
for p in "${system_pkgs[@]}"; do depends+=", $p"; done
cat >"$stage/DEBIAN/control" <<EOF
Package: xdviofdmx
Version: $deb_version
Section: tex
Priority: optional
Architecture: $arch
Maintainer: $maintainer
Depends: $depends
Recommends: python3-fonttools, ghostscript
Homepage: $homepage
Description: XDV 转 OFD（GB/T 33190）转换器，xdvipdfmx 的 OFD 对应物
 xdviofdmx 直接解析 XeTeX 的 XDV 事件流并生成 OFD 原生对象（文本、路径、
 图像），文字可选中、可复制、可搜索，也可作为 XeLaTeX 的输出驱动
 （xelatex -output-driver=xdviofdmx）。
 .
 Java 依赖为混合模式：Debian 已打包的 13 个库经 Depends 使用系统
 /usr/share/java 副本（见 /usr/share/xdviofdmx/classpath）；Debian 未
 提供的库（ofdrw、xdvjni、pdfbox 3.x 等）内嵌在 jar 中。
 字体子集化需 python3-fontTools（推荐），EPS 光栅化需 ghostscript；
 缺失时自动回退，不中断转换。
EOF

# ---- 打包 ----
mkdir -p -- "$out_dir"
out_file="$out_dir/xdviofdmx_${deb_version}_${arch}.deb"
dpkg-deb --build --root-owner-group "$stage" "$out_file"

echo
echo "已生成: $out_file"
echo "安装:   sudo apt install ./${out_file##*/}   或   sudo dpkg -i ${out_file##*/}"
