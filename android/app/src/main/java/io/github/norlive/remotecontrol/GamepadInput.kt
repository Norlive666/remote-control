package io.github.norlive.remotecontrol

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt

internal class GamepadInput(context: Context,private val vm: RemoteViewModel) : InputManager.InputDeviceListener {
    private val manager=context.getSystemService(InputManager::class.java)
    private val buttons=GamepadButtons()
    private var deviceId: Int?=null
    private var running=false
    private var focused=true
    fun start() { running=true;manager.registerInputDeviceListener(this,null);refresh() }
    fun stop() { running=false;manager.unregisterInputDeviceListener(this);buttons.clear();vm.suspendGamepad() }
    fun focus(value: Boolean) { focused=value;if(!value){buttons.clear();vm.suspendGamepad()} }
    private fun isGamepad(device: InputDevice)=device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
    private fun refresh() {
        val devices=manager.inputDeviceIds.mapNotNull(manager::getInputDevice).filter(::isGamepad)
        val selected=devices.firstOrNull { it.id==deviceId } ?: devices.firstOrNull()
        if(selected?.id!=deviceId) { buttons.clear();vm.suspendGamepad() }
        deviceId=selected?.id;vm.gamepadDevice(selected?.name)
    }
    override fun onInputDeviceAdded(id: Int) { refresh() }
    override fun onInputDeviceChanged(id: Int) { refresh() }
    override fun onInputDeviceRemoved(id: Int) {
        if(id==deviceId) { buttons.clear();deviceId=null;vm.suspendGamepad();vm.gamepadDevice(null) }
        refresh()
    }
    private fun select(id: Int,device: InputDevice?): Boolean {
        if(!running || !focused || vm.layoutEditing)return false
        if(deviceId!=null && deviceId!=id)return false
        deviceId=id;vm.gamepadDevice(device?.name ?: "原生安卓手柄")
        return true
    }
    fun key(event: KeyEvent): Boolean {
        if(!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK))return false
        val button=when(event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X;KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.L1;KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.L2;KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.R2
            else -> return false
        }
        if(!select(event.deviceId,event.device))return false
        if(event.action==KeyEvent.ACTION_UP)buttons.key(button,false)
        else if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && buttons.key(button,true))vm.gamepadPress(button)
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if(!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked!=MotionEvent.ACTION_MOVE)return false
        if(!select(event.deviceId,event.device))return false
        fun axis(preferred: Int,fallback: Int): Float {
            val device=event.device
            val selected=if(device==null || device.getMotionRange(preferred)!=null)preferred else fallback
            return event.getAxisValue(selected)
        }
        vm.axes(true,(event.getAxisValue(MotionEvent.AXIS_X)*1000).roundToInt(),(-event.getAxisValue(MotionEvent.AXIS_Y)*1000).roundToInt())
        vm.axes(false,(axis(MotionEvent.AXIS_Z,MotionEvent.AXIS_RX)*1000).roundToInt(),(-axis(MotionEvent.AXIS_RZ,MotionEvent.AXIS_RY)*1000).roundToInt())
        fun trigger(button: GamepadButton,preferred: Int,fallback: Int) {
            val value=axis(preferred,fallback)
            val range=event.device?.getMotionRange(preferred) ?: event.device?.getMotionRange(fallback)
            val normalized=if(range!=null && range.min<0 && range.range>0)(value-range.min)/range.range else value
            if(buttons.trigger(button,normalized.coerceIn(0f,1f)))vm.gamepadPress(button)
        }
        trigger(GamepadButton.L2,MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE)
        trigger(GamepadButton.R2,MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS)
        return true
    }
}
