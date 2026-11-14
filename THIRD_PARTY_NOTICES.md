# Avisos legales y licencias de terceros — Audiolibro

Este proyecto realiza toda la generación y reproducción de audiolibros de forma **100 % local y sin conexión** en el dispositivo, integrando los siguientes componentes y modelos de terceros.

---

## 1. Aviso sobre voces sintéticas

Las narraciones generadas por **Audiolibro** se producen de forma sintética en el dispositivo mediante un modelo neuronal de texto a voz entrenado con grabaciones humanas. No constituyen grabaciones literales ni actuaciones directas de los locutores originales del corpus de entrenamiento.

---

## 2. sherpa-onnx (`com.k2fsa.sherpa.onnx` v2.0.1)

- **Proyecto:** https://github.com/k2-fsa/sherpa-onnx
- **Licencia:** Apache License 2.0
- **Uso en esta app:** Motor de inferencia offline (ONNX Runtime + JNI `arm64-v8a`) para síntesis de voz Piper VITS sin conexión.

---

## 3. Modelo de voz Piper `es_ES-sharvard-medium`

- **Artefacto empaquetado:** `vits-piper-es_ES-sharvard-medium` (Piper VITS, 22.050 Hz, 2 locutores en español peninsular: ID `0` masculino / ID `1` femenino)
- **Licencia de los pesos y configuración (`es_ES-sharvard-medium.onnx`, `es_ES-sharvard-medium.onnx.json`, `tokens.txt`):** MIT License

---

## 4. Corpus SHARVARD

- **Autores:** Vincent Aubanel, María Luisa García Lecumberri y Martin Cooke
- **Referencia:** Aubanel, V., García Lecumberri, M. L., & Cooke, M. (2014). *The SHARVARD Corpus: A phonemically-balanced Spanish sentence resource for audiology*. International Journal of Audiology, 53(9), 633–638. DOI: `10.3109/14992027.2014.907507`
- **Origen:** University of Edinburgh DataShare (`http://hdl.handle.net/10283/2743`)
- **Licencia:** Creative Commons Attribution (CC BY 3.0 / CC BY 4.0)

---

## 5. eSpeak NG (`espeak-ng-data`)

- **Proyecto:** https://github.com/espeak-ng/espeak-ng
- **Licencia:** GNU General Public License v3.0 or later (`GPLv3+`)
- **Uso en esta app:** Datos fonéticos y conversión texto-a-fonemas para español (`es`) utilizados por `sherpa-onnx` durante la inferencia Piper VITS offline.
