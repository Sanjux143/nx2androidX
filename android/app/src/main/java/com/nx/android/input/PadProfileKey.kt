package com.nx.android.input

import android.view.InputDevice
import java.security.MessageDigest

/**
 * 一只设备是谁：descriptor + 厂商/型号 + 轴的能力签名，SHA-256 之后是它的档案键。
 *
 * 同一只手柄在换一台手机后还是这只（指纹与手机无关）；同型号的两只同时插着的时候分不清，
 * 就退化成会话级的 `temporary:<deviceId>`（这一次的配置不保存）。
 */
data class PadProfileIdentity(
    val key: String,
    val persistent: Boolean,
    val capabilitySignature: String,
    val modelTemplateKey: String?,
    val requiresConfirmation: Boolean,
)

internal data class PadAxisCapability(
    val axis: Int,
    val source: Int,
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float,
)

object PadProfileKey {

    /** 这只设备的指纹（peers 是同时接着的其它手柄：同型号的要把它们数进去）。 */
    fun forDevice(device: InputDevice, peers: Collection<InputDevice>? = null): PadProfileIdentity {
        val connected = peers ?: InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .toList()
        val gamepads = connected.filter { candidate ->
            !candidate.isVirtual &&
                (candidate.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    candidate.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }
        val capabilities = device.motionRanges
            .filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
            .map {
                PadAxisCapability(
                    axis = it.axis,
                    source = it.source,
                    min = it.min,
                    max = it.max,
                    flat = it.flat,
                    fuzz = it.fuzz,
                )
            }
        val signature = capabilitySignature(device.sources, capabilities)
        return resolve(
            device.descriptor,
            device.vendorId,
            device.productId,
            capabilities.map { it.axis },
            device.id,
            ambiguous = device.descriptor.isBlank() || gamepads.count { it.descriptor == device.descriptor } > 1,
            signature,
        )
    }

    /** 指纹的算法本身（纯函数：单元测试直接喂它）。 */
    fun resolve(
        descriptor: String?,
        vendorId: Int,
        productId: Int,
        axes: Collection<Int>,
        deviceId: Int,
        ambiguous: Boolean,
        capabilitySignature: String = axes.distinct().sorted().joinToString(","),
    ): PadProfileIdentity {
        val templateKey = if (vendorId != 0 && productId != 0) {
            "model:${vendorId.toString(16)}:${productId.toString(16)}:$capabilitySignature"
        } else {
            null
        }
        val requiresConfirmation = descriptor.isNullOrBlank() || ambiguous
        if (descriptor.isNullOrBlank() || ambiguous) {
            return PadProfileIdentity(
                key = "temporary:$deviceId",
                persistent = false,
                capabilitySignature = capabilitySignature,
                modelTemplateKey = templateKey,
                requiresConfirmation = requiresConfirmation,
            )
        }
        val material = buildString {
            append(descriptor.trim())
            append('|').append(vendorId).append('|').append(productId).append('|')
            append(capabilitySignature)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return PadProfileIdentity(
            key = "device:" + digest.take(16)
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
            persistent = true,
            capabilitySignature = capabilitySignature,
            modelTemplateKey = templateKey,
            requiresConfirmation = false,
        )
    }

    /** 轴的能力签名（轴的种类与量程）：同一个型号但轴不一样的手柄不是同一只。 */
    internal fun capabilitySignature(sources: Int, axes: Collection<PadAxisCapability>): String {
        val canonical = buildString {
            append(sources.toUInt().toString(16))
            axes.sortedWith(compareBy(PadAxisCapability::axis, PadAxisCapability::source)).forEach { axis ->
                append('|').append(axis.axis).append(':').append(axis.source.toUInt().toString(16))
                append(':').append(axis.min.toRawBits().toUInt().toString(16))
                append(':').append(axis.max.toRawBits().toUInt().toString(16))
                append(':').append(axis.flat.toRawBits().toUInt().toString(16))
                append(':').append(axis.fuzz.toRawBits().toUInt().toString(16))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
