# WatchDog

WatchDog turns an Android phone into a security camera with local recording and LAN access.

## CI Release Signing (No Java Required)

You can create a signing keystore without installing Java by using OpenSSL to generate a PKCS12 file.

```bash
# 1) Generate private key
openssl genpkey -algorithm RSA -out watchdog.key -pkeyopt rsa_keygen_bits:2048

# 2) Create a self-signed certificate (10,000 days)
openssl req -new -x509 -key watchdog.key -out watchdog.crt -days 10000 -subj "/CN=WatchDog"

# 3) Build a PKCS12 keystore
openssl pkcs12 -export -inkey watchdog.key -in watchdog.crt -out watchdog-release.p12 -name watchdog -passout pass:YOUR_PASSWORD
```

Base64-encode the keystore and add GitHub Secrets:

```bash
base64 -i watchdog-release.p12 > watchdog-release.p12.b64
```

Secrets to set in GitHub:

- `SIGNING_STORE_FILE_B64` = contents of `watchdog-release.p12.b64`
- `SIGNING_STORE_PASSWORD` = `YOUR_PASSWORD`
- `SIGNING_KEY_ALIAS` = `watchdog`
- `SIGNING_KEY_PASSWORD` = `YOUR_PASSWORD`
