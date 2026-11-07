# Microscopio Móvil

App web para el móvil (Android con Chrome, iPhone con Safari) que:

1. **Identifica las cámaras** del teléfono y sus prestaciones: resolución de vídeo y foto, fotogramas por segundo, zoom, tipo de enfoque, distancia mínima de enfoque, linterna, exposición, balance de blancos, ISO. Puntúa cada cámara y recomienda la mejor para usar como microscopio.
2. **Hace de microscopio**: al acercar el móvil a un objeto, detecta cuándo está nítido, ajusta el enfoque por su cuenta (barrido de enfoque en los móviles que lo permiten) y amplía la imagen hasta el aumento útil. Enciende la linterna sola si hay poca luz.

Controles del visor: **Auto** (enfoque y aumento automáticos), **Luz**, **Capturar** (guardar o compartir la imagen), **Ajustes** (aumento, enfoque y exposición manuales; ocular redondo, realce de contraste, retícula) y **Cámara** (cambiar de cámara trasera). Pellizca para ampliar y toca para reenfocar.

## Por qué hay que publicarla

Los navegadores solo dejan usar la cámara en páginas servidas por **https**. Si abres `index.html` desde el almacenamiento del móvil, la cámara no funcionará. La forma más sencilla y gratuita es GitHub Pages.

## Publicarla en GitHub Pages (unos 3 minutos)

1. Entra en github.com con tu cuenta y pulsa **New repository**. Nombre: `microscopio`. Márcalo como **Public** y pulsa **Create repository**.
2. En el repositorio vacío, pulsa **uploading an existing file** y arrastra los 5 archivos de esta carpeta: `index.html`, `manifest.webmanifest`, `sw.js`, `icon-192.png`, `icon-512.png` (este `LEEME.md` es opcional). Pulsa **Commit changes**.
3. Ve a **Settings › Pages**. En *Build and deployment*, elige **Deploy from a branch**, rama **main**, carpeta **/ (root)**, y pulsa **Save**.
4. Espera uno o dos minutos. La app quedará en `https://TU_USUARIO.github.io/microscopio/`.

## Instalarla en el móvil

- **Android (Chrome)**: abre la dirección, acepta el permiso de cámara y, en el menú ⋮, pulsa **Añadir a pantalla de inicio** o **Instalar aplicación**. Aparecerá con su icono, a pantalla completa y funcionando sin conexión.
- **iPhone (Safari)**: abre la dirección, pulsa **Compartir › Añadir a pantalla de inicio**.

## Límites que conviene conocer

- Ninguna cámara de móvil enfoca a 0 cm: la principal suele enfocar desde 7–10 cm y algunas desde 2–4 cm. Si se ve borroso, separa el móvil; la app compensa con el aumento.
- Muchos móviles Android no dejan usar su cámara macro desde el navegador. La app solo ve las cámaras que el sistema expone.
- El aumento más allá del "aumento útil" es digital: la imagen crece pero no muestra más detalle. Para aumentos reales mayores, una lente macro de clip (5–10 €) delante de la cámara funciona muy bien con esta app.
- En iPhone, Safari informa de menos datos de la cámara (no da el modelo exacto ni el enfoque manual), pero el microscopio funciona con el autoenfoque del sistema.
