# Monitor Térmico V6

Atualização visual e de exibição em segundo plano.

## Novidades
- Interface no mesmo estilo visual dos outros apps: gradiente roxo/azul, cartões claros arredondados e navegação inferior.
- Notificação térmica personalizável com título, CPU/bateria e detalhes opcionais.
- Indicador flutuante sobre outros apps, arrastável e com CPU, bateria ou ambos.
- Tamanho, estilo e opacidade do indicador configuráveis.
- Toque na bolha para abrir o Monitor Térmico.
- CPU continua usando sensor real quando disponível; quando o HyperOS bloqueia o sensor, a estimativa é marcada com `~`.

## Permissão do indicador flutuante
O Android exige a permissão especial “Exibir sobre outros apps”. O próprio app abre a tela correta quando a bolha é ativada.

## Observação
Enquanto a bolha estiver ativa, o Android exige um serviço em primeiro plano. Por isso pode existir uma notificação mínima mesmo quando a notificação térmica personalizada estiver desligada.
