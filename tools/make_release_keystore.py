import os
import secrets
import string
import subprocess

home = os.path.expanduser("~")
ks = os.path.join(home, ".android", "book-release.keystore")
alpha = string.ascii_letters + string.digits

# PATH has no keytool on this box; the JDK used by gradlew-local.sh does.
jh = os.environ.get("JAVA_HOME") or "D:/Program/Java21"
keytool = os.path.join(jh, "bin", "keytool" + (".exe" if os.name == "nt" else ""))
if not os.path.exists(keytool):
    keytool = "keytool"
print("keytool =", keytool.replace(os.sep, "/"))

# The previous attempt died while assembling the properties text, after keytool had
# already created the store, so that key had no recorded password. Replace it.
if os.path.exists(ks):
    os.remove(ks)
    print("removed orphan keystore from the failed attempt")

pw = "".join(secrets.choice(alpha) for _ in range(24))
r = subprocess.run(
    [
        keytool, "-genkeypair", "-v",
        "-keystore", ks, "-alias", "book",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storetype", "PKCS12",
        "-dname", "CN=xiaojoo, OU=bookreader, O=bookreader, C=CN",
        "-storepass", pw, "-keypass", pw,
    ],
    capture_output=True, text=True,
)
if r.returncode:
    print("keytool FAILED rc=", r.returncode, r.stderr[-400:])
    raise SystemExit(1)
print("keytool rc = 0")

props = (
    "# Release signing config. Never committed (.gitignore: keystore.properties, *.keystore, *.jks).\n"
    "# Without this file assembleRelease falls back to debug signing.\n"
    "storeFile=" + ks.replace(os.sep, "/") + "\n"
    "storePassword=" + pw + "\n"
    "keyAlias=book\n"
    "keyPassword=" + pw + "\n"
)
with open("H:/book/keystore.properties", "w", encoding="ascii") as fh:
    fh.write(props)
print("props written:", os.path.getsize("H:/book/keystore.properties"), "bytes; pw length", len(pw))

v = subprocess.run([keytool, "-list", "-keystore", ks, "-storepass", pw],
                   capture_output=True, text=True)
print("re-read rc =", v.returncode)
for line in v.stdout.splitlines():
    if "book" in line or "Valid" in line:
        print("  ", line.strip())
