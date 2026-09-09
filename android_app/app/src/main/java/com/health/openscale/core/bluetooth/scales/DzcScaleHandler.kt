package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.DeviceCapability
import com.health.openscale.core.bluetooth.DeviceSupport
import com.health.openscale.core.bluetooth.LinkMode
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

class DzcScaleHandler : ScaleDeviceHandler() {

    private val SERVICE_UUID = uuid16(0xFFF0)
    private val NOTIFY_CHAR_UUID = uuid16(0xFFF4)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()

        if (name.startsWith("DZC")) {
            return DeviceSupport(
                displayName = "DZC Smart Scale",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                linkMode = LinkMode.CONNECT_GATT
            )
        }

        return null
    }

    override fun onConnected(user: ScaleUser) {
        logI("Starting connection sequence for DZC scale.")

        setNotifyOn(
            SERVICE_UUID,
            NOTIFY_CHAR_UUID
        )

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: ScaleUser
    ) {

        if (characteristic != NOTIFY_CHAR_UUID || data.size < 11) {
            return
        }

        logD(
            "Measurement data received: ${data.toHexPreview(24)}"
        )

        // Validate packet header.
        if ((data[0].toInt() and 0xFF) != 0xCF) {
            return
        }

        // Validate XOR checksum across bytes 0..9.
        var checksum = 0

        for (i in 0..9) {
            checksum = checksum xor
                (data[i].toInt() and 0xFF)
        }

        if (checksum != (data[10].toInt() and 0xFF)) {
            logE("Checksum mismatch in DZC packet")
            return
        }

        // Weight: uint16 little-endian at bytes 3..4.
        val rawWeight =
            ((data[4].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)

        val weightKg = rawWeight / 100.0f

        // Impedance: uint16 little-endian at bytes 5..6.
        val rawImpedance =
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[5].toInt() and 0xFF)

        val impedanceOhm = rawImpedance / 10.0f

        // Status byte.
        val isStabilized =
            (data[8].toInt() and 0xFF) == 0x01

        val measurement = ScaleMeasurement().apply {
            userId = user.id

            this[MeasurementType.WEIGHT] =
                Kg(weightKg)

            if (impedanceOhm > 0f) {
                this[MeasurementType.IMPEDANCE] =
                    Ohm(impedanceOhm)
            }
        }

        if (isStabilized) {

            logI(
                "Stable weight measurement " +
                "($weightKg kg) received."
            )

            publish(measurement)

            requestDisconnect()
        }
    }
}