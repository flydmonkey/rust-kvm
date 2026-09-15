# Task 10 实施报告：CH9329 协议核心

## 完成内容

- 新增 `Ch9329`，按 `web/ch9329.js` 构建键盘、绝对鼠标和相对鼠标数据帧，并使用整帧低 8 位和生成校验字节。
- 支持键盘按下/抬起、最多 6 个普通键、8 类左右修饰键、全部按键释放及 Ctrl+Alt+Delete。
- 绝对坐标把归一化值钳制并映射至 `0..4095`；相对位移钳制至 `-127..127` 并按补码封包。
- 新增常用 `KeyboardEvent.code`、字符别名和 `CONTROL_KEY_BITS` 映射。

## 测试与验证

- TDD RED：新增测试首次运行因 `Ch9329` 尚不存在而按预期编译失败。
- 单元测试覆盖绝对坐标边界及钳制、相对位移补码、键盘与修饰键状态、固定释放包（14 字节，校验和 `0x0C`）及 Ctrl+Alt+Delete。
- JDK 17 下执行 `.\gradlew.bat :client:testDebugUnitTest --no-daemon`：`BUILD SUCCESSFUL`。
- ACK 队列按一期要求简化为立即写入，后续可在不改输入 API 的前提下补充。
