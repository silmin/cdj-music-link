#!/usr/bin/env bash
# macOS (Apple Silicon) の JVM 開発環境を作り直す。
#  1. x86_64 の JDK と Oracle Java 8 の残骸を撤去(見つかったものだけ)
#  2. 古い Gradle キャッシュ(~/.gradle)を削除
#  3. Eclipse Temurin 25 と Gradle(wrapper 生成用)を Homebrew で導入
# sudo のパスワードを途中で聞かれる。
set -euo pipefail

echo "== 1. x86_64 JDK の撤去"
for jdk in /Library/Java/JavaVirtualMachines/*; do
  if [ -L "$jdk" ] && [ ! -e "$jdk" ]; then
    echo "  dangling symlink: $jdk"; sudo rm -f "$jdk"; continue
  fi
  [ -e "$jdk" ] || continue
  bin="$jdk/Contents/Home/bin/java"
  if [ -x "$bin" ] && file -b "$bin" | grep -q x86_64 && ! file -b "$bin" | grep -q arm64; then
    echo "  x86_64 only: $jdk"; sudo rm -rf "$jdk"
  else
    echo "  keep: $jdk"
  fi
done

echo "== 2. Oracle Java 8 (Internet plug-in) の撤去"
sudo launchctl bootout system /Library/LaunchDaemons/com.oracle.java.Helper-Tool.plist 2>/dev/null || true
launchctl bootout "gui/$(id -u)" /Library/LaunchAgents/com.oracle.java.Java-Updater.plist 2>/dev/null || true
sudo rm -rf "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin" \
  /Library/PreferencePanes/JavaControlPanel.prefPane \
  /Library/LaunchAgents/com.oracle.java.Java-Updater.plist \
  /Library/LaunchDaemons/com.oracle.java.Helper-Tool.plist \
  /Library/Preferences/com.oracle.java.Helper-Tool.plist \
  /Library/PrivilegedHelperTools/com.oracle.java.JavaUpdateHelper
rm -rf "$HOME/Library/Application Support/Oracle" "$HOME/Library/Preferences/com.oracle.java.Java-Updater.plist"

echo "== 3. 古い Gradle キャッシュの削除"
rm -rf "$HOME/.gradle"

echo "== 4. Temurin 25 と Gradle の導入"
brew install --cask temurin@25
brew install gradle

echo "== 5. 確認"
/usr/libexec/java_home -V 2>&1 || true
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
"$JAVA_HOME/bin/java" -version
gradle --version | head -3
echo "done. 新しいシェルを開くか 'source ~/.config/fish/conf.d/java.fish' で JAVA_HOME が有効になります。"
