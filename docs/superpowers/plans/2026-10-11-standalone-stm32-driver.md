# Standalone STM32 Driver Implementation Plan

> **For agentic workers:** Use executing-plans for inline execution, with requesting-code-review before delivery.

**Goal:** 两文件和一行 Init 完成 USART1/TIM4 接入，避免生成文件符号冲突，忙时保留原工程。

**Architecture:** 两个型号保留同样的协议实现。寄存器配置代替 UART/TIM HAL 初始化，私有中断通过复制并迁移向量表安装。

**Tech Stack:** C11、STM32 HAL/RCC、CMSIS、GCC ARM 交叉编译器。

## Global Constraints

- 支持 STM32F103C8T6、STM32H723VGT6；默认 USART1 PA9/PA10、TIM4。
- 不改 Android、线上协议、g_rc 布局、300 ms 超时。
- 初次初始化外设时钟或目标 IRQ 已启用则 HAL_BUSY，失败不能修改硬件或 g_rc。
- 保留系统时钟、共享时钟源、其他向量及中断分组。
- 相同 baud 再次初始化 HAL_OK，不重置；不同 baud HAL_BUSY。

### Task 1: 验证最少依赖与生成文件共存

**Files:** tools/compile-stm32.sh、tests/c/test_generated_handlers.c。
**Interfaces:** 编译对应 remote_control.c；真实官方芯片头文件；链接桩提供基础 RCC 时钟函数和生成的强 IRQ。

- [x] 从测试 HAL 配置中去掉 UART/TIM/DMA/GPIO/CORTEX，只保留 HAL/RCC。
- [x] 链接桩定义 USART1_IRQHandler/TIM4_IRQHandler，链接整个驱动对象，禁止未解析符号。
- [x] 执行 `STM32_H7_CUBE_DIR=/tmp/rc-standalone-deps/H7 STM32_F1_CUBE_DIR=/tmp/rc-standalone-deps/F1 tools/compile-stm32.sh`，确认原驱动因 UART/TIM 依赖失败。

### Task 2: 初始化与中断行为

**Files:** stm32/f103/remote_control.c/.h、stm32/h723/remote_control.c/.h、tests/c/hardware/、tests/c/test_init.c、tests/c/run-hardware.sh。
**Interfaces:** 保留 `HAL_StatusTypeDef RC_InitBaud(uint32_t baud)`、`g_rc` 和所有既有公开函数。

- [x] 用官方芯片寄存器定义配合主机映射内存与 CMSIS 测试替身执行真实驱动，测试 busy 不变、非法 baud 不变、向量保留、默认引脚/波特率/1 ms、重复初始化、UART 接收、TIM4 超时。
- [x] 寄存器直接初始化，不调用生成 MSP；预检查参数和资源后进入安装。
- [x] 复制完整向量表到对齐 RAM 表；仅替换 USART1/TIM4，保留 PRIMASK；H7 cache clean 加屏障。
- [x] 两型号运行硬件初始化行为测试、`tests/c/run.sh` 与目标编译链接检查。

### Task 3: 文档、审阅和交付

**Files:** docs/stm32.md、docs/validation.md、README.md、.github/workflows/build.yml。

- [x] 更新接入示例、HAL_BUSY/资源边界、RAM 成本与实机验证限制；CI 增加初始化测试。
- [x] 请求只读代码审阅，修正实质问题，再运行受影响测试。
- [x] 提交并推送已授权仓库，发布驱动压缩包与校验和；APK 保持 v1.4.0。
