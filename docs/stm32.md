# STM32 接收端接入

`stm32/h723/` 适用于 STM32H723VGT6，`stm32/f103/` 适用于 STM32F103C8T6。每个目录的 `remote_control.c` 和 `remote_control.h` 是独立的两文件包，只复制对应目录中的两文件到自己的 HAL 工程。工程仍须提供该芯片的 HAL、CMSIS、启动文件、`HAL_Init()` 和系统时钟配置；本驱动不生成完整工程。

```c
HAL_Init();
SystemClock_Config();
if (RC_Init() != HAL_OK) Error_Handler();       /* HC-05，9600 bit/s */
/* 或 RC_InitBT630()：FSC-BT630，115200 bit/s。其他波特率用 RC_InitBaud(baud)。 */

RC_State_t state;
RC_GetState(&state); /* 在主循环或其他中断读取一致的快照 */
if (state.link_valid && state.enabled && !state.stopped) {
    /* 使用 axes[0..3]：-1000..1000，sliders[0..1]：0..1000 */
}
```

接线按芯片选择，两端共地，供电和电平按模块规格：

| 芯片 | MCU TX → 模块 RX | MCU RX ← 模块 TX | 定时器 | 中断 |
| --- | --- | --- | --- | --- |
| STM32H723VGT6 | PD8 / USART3 TX（AF7） | PD9 / USART3 RX（AF7） | TIM5 | USART3、TIM5 |
| STM32F103C8T6 | PA9 / USART1 TX | PA10 / USART1 RX | TIM4 | USART1、TIM4 |

H723 的 PD8/PD9 AF7 定义见 [ST 数据手册](https://www.st.com/resource/en/datasheet/stm32h723vg.pdf)。

## 两文件、一行初始化

1. 将对应型号的 `remote_control.c` 加入编译，将 `remote_control.h` 所在目录加入头文件搜索路径；在使用处 `#include "remote_control.h"`。
2. 在 `HAL_Init()`、`SystemClock_Config()` 后加入 `if (RC_Init() != HAL_OK) Error_Handler();`，或者在业务程序中检查并处理返回状态。BT630 使用 `RC_InitBT630()`；实际模块已修改波特率时使用 `RC_InitBaud(baud)`。
3. 无需 `.ioc` 中配置 对应 GPIO/USART/定时器，无需 `uart.c`、`tim.c`，无需开启 HAL UART/TIM/GPIO/DMA 模块，无需编辑中断函数。工程保留基础 HAL/RCC（H723 包括 RCCEx）、芯片 CMSIS、启动文件、链接脚本和系统时钟。

驱动直接配置 对应 GPIO/USART/定时器 寄存器，不调用生成的 MSP 回调。使用私有中断函数，复制当前向量表到对齐 RAM 后仅替换所选串口、定时器两项（H723 USART3/TIM5，F103 USART1/TIM4）；不导出所选外设的生成器常用 IRQ 或 HAL 句柄名称。因此原有 `uart.c`、`tim.c` 和 `stm32xx_it.c` 可以保留并一起编译，不需要合并同名中断函数；其他中断入口和优先级分组保持不变。RAM 向量表内容占 F103 236 字节、H723 716 字节，另有链接对齐开销。H723 在数据缓存开启时清理表缓存，切换 VTOR 前后使用屏障。

## 占用与返回状态

- `HAL_OK`：初始化成功；相同波特率再次调用也是 `HAL_OK`，不重置数据或计数器。
- `HAL_BUSY`：所选 USART 或定时器的外设时钟已启用，或目标中断已启用；不修改原外设、向量表和 `g_rc`。F103 的 USART1 已重映射到其他引脚也返回此状态，并保留重映射与调试设置。驱动初始化成功后改用其他波特率调用也返回 `HAL_BUSY`。
- `HAL_ERROR`：波特率或时钟不能生成支持的串口/定时分频；不修改原硬件和 `g_rc`。

文件共存不代表同一个外设能同时承担两种用途。H723 原工程若已调用 `MX_USART3_UART_Init()` 或 `MX_TIM5_Init()`，驱动返回 `HAL_BUSY`；F103 对应为 `MX_USART1_UART_Init()`、`MX_TIM4_Init()`。使用本驱动时应避免执行对应生成初始化函数，也不要在驱动成功后重新配置它占用的外设和引脚。H723 原有 USART1、TIM4 可以继续使用。引脚必须预留；驱动不能识别原工程以后才执行的配置，也不能自动判断所有引脚用途。

请勿在初始化后覆盖或再次迁移中断向量表；自定义启动、RTOS 或引导程序有自己的向量移交步骤时，应在其最后一次移交之后初始化本驱动。标准 CubeIDE/GCC 链接脚本无需新增驱动专用段。系统时钟和所选 USART 的内核时钟源由原工程设定，驱动按现有时钟计算分频，不修改共享时钟源；初始化后不要改变这些时钟。

H723 驱动直接读取 USART3 的内核时钟选择，支持 PCLK1、PLL2Q、PLL3Q、分频后的 HSI、CSI 和 LSE。所选独立时钟未就绪或 PLL 的 Q 输出未启用时返回 `HAL_ERROR`。v2.1.0 使用的 `HAL_RCCEx_GetPeriphCLKFreq()` 在本工程的 H7 HAL 中不支持 USART 查询，会返回 0 并导致初始化失败；v2.1.1 修复此问题，无需修改 `.ioc` 或共享时钟源。

协议为 20 字节固定帧：`A5 5A`、版本 `01`、8 位序号、四个小端有符号轴值、两个小端无符号滑块值、标志、零保留字节、覆盖字节 2–17 的小端 CRC16/CCITT-FALSE。坏 CRC、版本、保留位、越界值、重复或过旧序号不更新状态，也不喂狗。序号按模 256 前进，差值 1–127 视为新帧。禁用或停止帧必须携带全零轴值和滑块值，否则整帧丢弃且不刷新超时计时；合法的禁用或停止帧有效控制值为零。定时器（H723 TIM5、F103 TIM4）根据当前 APB1 分频（H7 还包括 TIMPRE）设置约 1 ms 中断；300 个计时周期没有新有效帧就强制禁用并置停止。初始化、手动重置或超时后都必须先收到全零且有效的禁用帧，才能建立序号基线；只有后续新序号的启用帧才能控制。若 MCU 重启时手机仍持续发送启用帧，输出会保持中立，直到手机明确发送禁用帧。状态发布和超时置中立时也会短暂保存、关闭并恢复 PRIMASK；`RC_GetState()` 使用同一保护读取完整快照，因此更高优先级中断中调用快照函数也不会读到半更新状态。直接读取全局 `g_rc` 的多个字段无法保证一致性。`link_valid` 在接受新有效帧后为 1，初始化和超时后为 0；`valid_frames` 统计接受的新帧（含超时后合法的禁用重建帧），`crc_errors` 仅统计完整候选帧的 CRC 错误。超时清零控制值和 `link_valid`，保留两个计数器；`RC_Reset()` 会清零计数器；同波特率重复初始化不会重置。

`tests/c/run.sh` 在主机执行两个包的解析/安全行为测试。初始化行为测试 `tests/c/run-hardware.sh` 使用官方设备寄存器定义、主机映射内存及 CMSIS 测试替身，覆盖忙时保留、错误路径、向量保留、中断接收、超时和重复初始化；它不模拟真实 UART 波形、缓存或中断调度。目标编译及生成中断共存链接检查使用 `tools/compile-stm32.sh`，通过 `STM32_H7_CUBE_DIR`、`STM32_F1_CUBE_DIR` 指向对应官方 STM32Cube 固件根目录。编译检查和主机测试都不能替代实际开发板、蓝牙模块和中断时序验证。
