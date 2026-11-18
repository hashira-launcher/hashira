# hashira 柱

[English](README.md) · **Español** · [日本語](README.ja.md)

Un launcher de Android minimalista hecho para GrapheneOS. Kotlin y Jetpack Compose, sin analítica ni servicios de Google. Versión 0.0.2.

## Características

- Pantalla de inicio con una columna de apps favoritas, lista completa A–Z con búsqueda y apps frecuentes
- Icon packs (`appfilter.xml` de ADW, Nova y Apex), apps ocultas protegidas con huella o PIN
- Compatible con Espacio privado y perfil de trabajo
- Pronóstico del clima opcional (Open-Meteo), puntos de notificación y doble toque para bloquear la pantalla
- Interfaz en inglés, español y japonés

Requiere Android 13 o superior (minSdk 33).

## Requisitos

- Linux x86_64 (los ejemplos de abajo son para Arch Linux)
- JDK 17 o superior
- Android SDK con la plataforma 37 y build-tools 36.0.0
- git

## Preparar el entorno (Arch Linux)

```sh
sudo pacman -S --needed jdk17-openjdk git unzip android-tools
```

Si tienes varios JDK instalados, elige uno (17 o superior) con `archlinux-java status` y `sudo archlinux-java set <nombre>`.

Instala las herramientas de línea de comandos del Android SDK. Descarga el zip de Linux "Command line tools only" desde <https://developer.android.com/studio#command-tools> y luego:

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

Agrega las dos líneas `export` a tu perfil de shell (`~/.bashrc` o `~/.zshrc`) para conservarlas. Gradle también descarga los componentes del SDK que falten una vez aceptadas las licencias.

## Compilar

```sh
git clone https://github.com/hashira-launcher/hashira.git
cd hashira
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Las compilaciones debug son bastante más lentas que las release, así que usa una release para el uso diario.

## Compilación release

Las versiones release se firman con tu propia clave. La clave nunca se guarda en el repositorio.

### 1. Crear un keystore (una sola vez)

Guárdalo fuera del repositorio y haz una copia de seguridad. Si lo pierdes, no podrás publicar actualizaciones que se instalen sobre la versión anterior.

```sh
mkdir -p ~/.keystores
keytool -genkeypair -v -keystore ~/.keystores/hashira-release.jks \
  -alias hashira -keyalg RSA -keysize 4096 -validity 10000
```

### 2. Indicar dónde está

Opción A, un archivo `keystore.properties` (está en `.gitignore`):

```sh
cp keystore.properties.example keystore.properties
chmod 600 keystore.properties
# edítalo: ruta absoluta en storeFile, contraseñas y alias
```

Con el keystore PKCS12 por defecto, `keyPassword` debe ser igual a `storePassword`.

Opción B, variables de entorno (tienen prioridad sobre el archivo):

```sh
export HASHIRA_KEYSTORE_FILE=/home/tu-usuario/.keystores/hashira-release.jks
export HASHIRA_KEYSTORE_PASSWORD=...
export HASHIRA_KEY_ALIAS=hashira
export HASHIRA_KEY_PASSWORD=...
```

### 3. Ejecutar el script de release

```sh
./release.sh
```

Revisa tu configuración, compila el APK firmado, verifica la firma, genera un checksum y crea una etiqueta git local. La versión sale de `hashira.versionName` en `gradle.properties`.

```
dist/hashira-0.0.2.apk
dist/hashira-0.0.2.apk.sha256
```

Usa `./release.sh --no-tag` para omitir la etiqueta git. Sube la etiqueta con `git push origin v0.0.2` y adjunta los dos archivos a un release de GitHub.

Sin configuración de firma, `./gradlew assembleRelease` funciona igual pero produce un APK sin firmar que Android no instalará.

### Verificar un release descargado

```sh
sha256sum -c hashira-0.0.2.apk.sha256
apksigner verify --print-certs hashira-0.0.2.apk
```

Compara la huella del certificado con la publicada por los mantenedores.

## Instalar

```sh
adb install -r dist/hashira-0.0.2.apk
```

O copia el APK al teléfono y ábrelo desde un gestor de archivos. Después elige hashira como app de inicio: Ajustes → Apps → Apps predeterminadas → App de inicio.

Las actualizaciones deben estar firmadas con la misma clave que la versión instalada.

## Privacidad y permisos

| Permiso | Para qué se usa |
| --- | --- |
| `INTERNET` | Pronóstico del clima de Open-Meteo, solo si lo activas. Puedes revocarlo en los ajustes de Android y el launcher sigue funcionando. |
| `ACCESS_COARSE_LOCATION` | Ubicación aproximada para el clima, se pide solo si eliges "Ubicación del teléfono". |
| `ACCESS_HIDDEN_PROFILES` | Mostrar el Espacio privado (Android 15+), solo cuando hashira es el launcher predeterminado. |
| `USE_BIOMETRIC` | Huella o PIN para abrir la lista de apps ocultas. |

Dos componentes opcionales necesitan tu aprobación extra en los ajustes de Android: un administrador de dispositivo que solo puede bloquear la pantalla (doble toque para bloquear; Android impide desinstalar la app mientras está activo) y el acceso a notificaciones para los puntos de notificación (solo nombres de paquete, nunca el contenido).

## Estructura del proyecto

```
app/src/main/java/com/hashira/launcher/   código Kotlin (ui, data, icons, weather)
app/src/main/res/                         recursos y traducciones (en, es, ja)
gradle/libs.versions.toml                 versiones de dependencias y plugins
gradle.properties                         versión de la app (hashira.versionName / versionCode)
release.sh                                compilación release firmada
```

## Licencia

MIT, ver [LICENSE](LICENSE). El icono del launcher es el glifo 柱 de Noto Sans CJK JP Bold (SIL Open Font License 1.1), convertido a trazado vectorial.
