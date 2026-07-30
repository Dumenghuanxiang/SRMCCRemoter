#include "main.h"
#include "srm_stm32f103_port.h"

#include <string.h>

/*
 * 教学用板级映射示例，不应原样控制真实机器人。
 * CubeMX 需额外配置 TIM2 CH1..CH4 为 50 Hz PWM：计数频率 1 MHz，Period=19999。
 * 本例将四轴映射为 1000..2000 us 脉宽，并用 PC13 LED 显示 A 键状态。
 */
extern TIM_HandleTypeDef htim2;

static uint16_t axis_to_servo_us(int16_t axis) {
    int32_t divisor = axis < 0 ? 512 : 511;
    int32_t pulse = 1500 + ((int32_t)axis * 500) / divisor;
    if (pulse < 1000) pulse = 1000;
    if (pulse > 2000) pulse = 2000;
    return (uint16_t)pulse;
}

void SRM_BoardApplyControl(const srm_control_state_t *state) {
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, axis_to_servo_us(state->left_x));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_2, axis_to_servo_us(state->left_y));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_3, axis_to_servo_us(state->right_x));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_4, axis_to_servo_us(state->right_y));

    /* Blue Pill 的 PC13 LED 通常低电平点亮；这里只把 A 键作为可视化示范。 */
    HAL_GPIO_WritePin(GPIOC, GPIO_PIN_13,
            (state->buttons & 0x01u) != 0u ? GPIO_PIN_RESET : GPIO_PIN_SET);

    /*
     * 其余中性输入可在这里定义：
     * buttons bit0..3 = A/B/X/Y
     * switches bit0..5 = S1..S6
     * dpad = 0中立、1上、2下、3左、4右
     */
}

void SRM_BoardApplyProControl(const srm_pro_control_state_t *state) {
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, axis_to_servo_us(state->left_x));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_2, axis_to_servo_us(state->left_y));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_3, axis_to_servo_us(state->right_x));
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_4, axis_to_servo_us(state->right_y));
    HAL_GPIO_WritePin(GPIOC, GPIO_PIN_13,
            (state->buttons & SRM_PRO_BUTTON_A) != 0u ? GPIO_PIN_RESET : GPIO_PIN_SET);

    /* LT/RT 是 0..255；按键位定义见 srm_protocol.h。 */
}

uint8_t SRM_BoardHandleDebug(const uint8_t *utf8, uint8_t length) {
    static const uint8_t ping[] = "PING";
    static const uint8_t led_on[] = "LED=1";
    static const uint8_t led_off[] = "LED=0";

    if (utf8 == NULL) return 1u;
    /* App 默认追加 LF；同时兼容手动输入的 CRLF 和关闭自动换行的指令。 */
    if (length > 0u && utf8[length - 1u] == '\n') length--;
    if (length > 0u && utf8[length - 1u] == '\r') length--;
    if (length == sizeof(ping) - 1u
            && memcmp(utf8, ping, sizeof(ping) - 1u) == 0) return 0u;
    if (length == sizeof(led_on) - 1u
            && memcmp(utf8, led_on, sizeof(led_on) - 1u) == 0) {
        HAL_GPIO_WritePin(GPIOC, GPIO_PIN_13, GPIO_PIN_RESET);
        return 0u;
    }
    if (length == sizeof(led_off) - 1u
            && memcmp(utf8, led_off, sizeof(led_off) - 1u) == 0) {
        HAL_GPIO_WritePin(GPIOC, GPIO_PIN_13, GPIO_PIN_SET);
        return 0u;
    }
    return 1u;
}
