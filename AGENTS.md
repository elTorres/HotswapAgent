# Instrucciones de desarrollo

## Forma de trabajar

- Lee las instrucciones y el código afectado antes de modificarlo. Respeta versiones, herramientas y convenciones del proyecto; consulta documentación oficial cuando una API o su compatibilidad sea incierta.
- Resuelve el objetivo con el cambio más sencillo que lo satisfaga. Aplica Clean Code, KISS y YAGNI: nombres claros, responsabilidades concretas y abstracciones justificadas por necesidades actuales.
- No conviertas una corrección pequeña en una migración, un framework o una reorganización general. Introduce dependencias solo cuando aporten una ventaja concreta.
- Conserva el trabajo ajeno. Explica las decisiones relevantes y pregunta únicamente por ambigüedades que afecten al resultado; continúa con lo que ya esté definido.
- Los roles especializados son opcionales. La disponibilidad de un rol no obliga a delegar ni a ampliar el alcance de una tarea.
- Si una petición admite varias interpretaciones, preséntalas en lugar de elegir una en silencio. Indica las suposiciones relevantes.
- Limita los cambios a lo solicitado: no reformatees ni mejores código adyacente y sigue el estilo existente. Elimina solo los imports, variables o funciones que tu cambio deje sin uso; menciona el código muerto previo sin borrarlo.

## Diseño y verificación

- En backend, separa reglas de negocio e integración. La arquitectura hexagonal se aplica al backend: dominio y aplicación independientes de HTTP, persistencia y frameworks; puertos para dependencias externas reales.
- En frontend, organiza componentes, estado y acceso a datos según su responsabilidad. Extrae un composable o servicio cuando simplifique el comportamiento o exista reutilización real.
- Mantén una aplicación y un módulo como punto de partida. Añade módulos, mensajería o servicios separados cuando exista una necesidad demostrable.
- Valida entradas en las fronteras y protege invariantes dentro del dominio. Maneja errores con información útil sin exponer datos sensibles.
- Verifica el comportamiento cambiado con las pruebas y comprobaciones apropiadas del proyecto. Prioriza casos normales, límites y fallos relevantes sobre porcentajes de cobertura.
- Distingue lo ejecutado de lo inferido. Si falta una dependencia o plataforma, indica qué no se ha podido verificar y cómo comprobarlo.
- Antes de implementar, convierte la tarea en criterios verificables (por ejemplo, una prueba que reproduzca el fallo) y repite hasta cumplirlos. En tareas de varios pasos, indica cada paso con su comprobación.
- Documenta cualquier suposición, limitación o decisión relevante que afecte al diseño o la verificación. Esto facilita la revisión y el mantenimiento futuro.

## Git y comunicación

- Revisa el estado y el diff antes de editar y al terminar, cuando haya Git disponible. No sobrescribas cambios ajenos ni reescribas historial sin que forme parte de la petición.
- Comunica primero el resultado; añade la razón del cambio y las pruebas realizadas. Usa lenguaje directo y concreto, con detalle proporcional al problema.
- Evita afirmaciones de seguridad, rendimiento o compatibilidad que no estén respaldadas por evidencia.
