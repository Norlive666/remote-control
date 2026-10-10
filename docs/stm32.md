# STM32 接收端接入

`stm32/h723/` 适用于 STM32H723VGT6，`stm32/f103/` 适用于 STM32F103C8T6。每个目录的 `remote_control.c` 和 `remote_control.h` 是独立的两文件包，只复制对应目录中的两文件到自己的 HAL 工程。工程仍须提供该芯片的 HAL、CMSIS、启动文件、`HAL_Init()` 和系统时钟配置；本驱动不生成完整工程。

```c
HAL_Init();
SystemClock_Config();
if (RC_Init() != HAL_OK) Error_Handler();       /* HC-05，9600 bit/s */
/* 或 RC_InitBT630()：FSC-BT630，115200 bit/s。其他波特率用 RC_InitBaud(baud)。 */

RC_State_t state;
RC_GetState(&state); /* 在主循环或其他中断读取一致的快照 */
if (state.enabled && !state.stopped) {
    /* 使用 axes[0..3]：-1000..1000，sliders[0..1]：0..1000 */
}
```

接线：模块 TX → MCU PA10 (USART1 RX)，模块 RX ← MCU PA9 (USART1 TX)，两端共地，供电和电平按模块规格。驱动使用 USART1 PA9/PA10、TIM4 和对应 NVIC 中断；初始化自行打开 GPIOA、USART1、TIM4 时钟并配置引脚、UART、定时器和中断。请勿再调用这些资源的 CubeMX 初始化函数。`remote_control.c` 定义 `USART1_IRQHandler`、`TIM4_IRQHandler`；如果工程已定义这两个符号，应只保留一个入口，并把本文件中的接收/计时处理合并到现有中断流程。不要同时调用 HAL 的 UART 接收中断处理，因为本驱动直接读接收寄存器；其他 HAL 弱回调不会被覆盖。建议只初始化一次，开始使用前先确认 `RC_Init*()` 返回 `HAL_OK`。

协议为 20 字节固定帧：`A5 5A`、版本 `01`、8 位序号、四个小端有符号轴值、两个小端无符号滑块值、标志、零保留字节、覆盖字节 2–17 的小端 CRC16/CCITT-FALSE。坏 CRC、版本、保留位、越界值、重复或过旧序号不更新状态，也不喂狗。序号按模 256 前进，差值 1–127 视为新帧。禁用或停止帧必须携带全零轴值和滑块值，否则整帧丢弃且不刷新超时计时；合法的禁用或停止帧有效控制值为零。TIM4 根据当前 APB1 分频（H7 还包括 TIMPRE）设置约 1 ms 中断；300 个计时周期没有新有效帧就强制禁用并置停止。超时后必须先收到有效的禁用帧才能重新建立序号基线，再收到后续新序号的启用帧才能控制，避免旧数据积压直接恢复输出。`RC_GetState()` 保存并恢复 PRIMASK，确保读到完整快照；直接读取全局 `g_rc` 的多个字段无法保证一致性。

`tests/c/run.sh` 在主机执行两个包的解析/安全行为测试。目标编译检查使用 `tools/compile-stm32.sh`，通过 `STM32_H7_CUBE_DIR`、`STM32_F1_CUBE_DIR` 指向对应官方 STM32Cube 固件根目录。编译检查和主机测试都不能替代实际开发板、蓝牙模块和中断时序验证。
