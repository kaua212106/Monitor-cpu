# Monitor Térmico V5.1 — fallback sem Shizuku

Esta atualização foi feita após analisar o APK de referência enviado pelo usuário.

## Descoberta importante

O app de referência primeiro tenta ler arquivos térmicos do sistema diretamente.
Quando nenhum deles funciona, ele não fica necessariamente sem temperatura:
ele calcula um valor aproximado usando a temperatura da bateria e a relação entre
a frequência atual e a frequência máxima da CPU.

A V5.1 implementa um fallback equivalente, mas identifica claramente o resultado
como **estimativa**, para não confundir com um sensor físico real.

## Ordem de leitura

1. Sensor real identificado de CPU/SoC.
2. Thermal zone legível sem nome, marcado como provável.
3. Estimativa por bateria + frequência, marcada como estimada.

Não usa Shizuku e não requer root.
