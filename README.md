# Monitor Térmico V5 — leitura direta sem Shizuku

Esta versão foi refeita para tentar a temperatura da CPU/SoC sem Shizuku e sem root.

## Como a leitura funciona

O app não depende de listar `/sys/class/thermal`, porque alguns aparelhos Xiaomi/HyperOS podem bloquear a listagem e ainda permitir a leitura de arquivos individuais.

A V5 tenta diretamente:

- caminhos conhecidos `cpu_temp` por núcleo;
- `/sys/class/hwmon/hwmon0/device/temp1_input` e outros `hwmon`;
- `/sys/class/thermal/thermal_zone0/temp` até `thermal_zone99/temp`;
- `/sys/devices/virtual/thermal/thermal_zone0/temp` até `thermal_zone99/temp`;
- caminhos legados usados por vários aparelhos Android.

Quando o arquivo `type` ou o rótulo do sensor também é legível, o app classifica CPU/SoC, GPU, bateria, carregamento, superfície, modem, Wi-Fi, NPU/APU etc.

Se a temperatura estiver legível mas o nome do sensor estiver bloqueado, a interface marca a leitura da CPU como **provável**, em vez de afirmar que o sensor foi identificado com certeza.

## Mudanças da V5

- remove Shizuku completamente;
- remove dependências Shizuku do Gradle;
- remove provider/permissão Shizuku do Manifest;
- leitura direta dos arquivos térmicos;
- tenta thermal zones sem `listFiles()`;
- tenta hwmon sem `listFiles()`;
- frequências da CPU também são tentadas diretamente em `cpu0` até `cpu15`;
- botão “Reexaminar sensores” para forçar uma nova descoberta;
- mantém bateria, Thermal Headroom, throttling e Modo Sessão/Jogo.
