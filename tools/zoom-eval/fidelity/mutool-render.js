// mutool run mutool-render.js PDF PAGE W H PW PH OUT [rgb]
// Draws page PAGE (0-based) into exactly W x H pixels on white with the device's matrix:
// scale(W / PW, H / PH), PW x PH being the page's integer size as Android reports it.
// Investigation tool for notes/renderer-fidelity.md.
var a = scriptArgs;
var doc = Document.openDocument(a[0]);
var page = doc.loadPage(parseInt(a[1]));
var w = parseInt(a[2]), h = parseInt(a[3]), pw = parseFloat(a[4]), ph = parseFloat(a[5]);
var cs = a[7] == "rgb" ? ColorSpace.DeviceRGB : ColorSpace.DeviceGray;
var pix = new Pixmap(cs, [0, 0, w, h], false);
pix.clear(255);
var dev = new DrawDevice(Matrix.identity, pix);
page.run(dev, Matrix.scale(w / pw, h / ph));
dev.close();
pix.saveAsPNM(a[6]);
