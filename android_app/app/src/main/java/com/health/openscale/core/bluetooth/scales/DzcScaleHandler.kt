Yes. I checked the current implementation of StandardImpedanceLib usage in VitafitVT701Handler, and the exact import/API is now verified. �
GitHub
Replace your handler with this version:
/*
 * openScale
 *
 * DZC-D18E3 Smart Scale BLE Handler
 *
 * Protocol:
 * - Manufacturer: Bear Electric Appliance (Xiong)
 * - BLE name prefix: DZC
 * - Service: 0xFFF0
 * - Command characteristic: 0xFFF1 (WRITE)
 * - Data characteristic: 0xFFF4 (NOTIFY)
 *
 * Notification packet (11 bytes):
 *
 * [0]     Header       uint8       0xCF
 * [1..2]  Auxiliary    uint16 LE
 * [3..4]  Weight       uint16 LE   / 100.0 kg
 * [5..6]  Impedance    uint16 LE   / 10.0 ohm
 * [7]     Profile ID   uint8
 * [8]     Status       0x00 live, 0x01 stabilized
 * [9]     Mode flags   uint8
 * [10]    Checksum     XOR bytes 0..9
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

class DzcScaleHandler : ScaleDeviceHandler() {

    companion object {
        private const val PACKET_LENGTH = 11
        private const val PACKET_HEADER = 0xCF

        private const val STATUS_DYNAMIC = 0x00
        private const val STATUS_STABILIZED = 0x01

        private const val MIN_VALID_IMPEDANCE = 1f
        private const val MAX_VALID_IMPEDANCE = 1500f
    }

    private val serviceUuid = uuid16(0xFFF0)
    private val notifyCharUuid = uuid16(0xFFF4)

    private var published = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.startsWith("DZC", ignoreCase = true)) {
            return null
        }

        val capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        )

        return DeviceSupport(
            displayName = "DZC Smart Scale",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        published = false

        logI("Connected to DZC scale. Enabling measurement notifications.")

        setNotifyOn(
            serviceUuid,
            notifyCharUuid
        )

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: ScaleUser
    ) {
        if (characteristic != notifyCharUuid) {
            return
        }

        if (published) {
            return
        }

        if (data.size != PACKET_LENGTH) {
            logD(
                "Ignoring DZC packet with unexpected length: ${data.size}"
            )
            return
        }

        val header = data[0].toInt() and 0xFF

        if (header != PACKET_HEADER) {
            logD(
                "Ignoring DZC packet with invalid header: " +
                    "0x%02X".format(header)
            )
            return
        }

        /*
         * Verify XOR checksum across bytes 0 through 9.
         */
        var checksum = 0

        for (i in 0 until 10) {
            checksum = checksum xor
                (data[i].toInt() and 0xFF)
        }

        val packetChecksum = data[10].toInt() and 0xFF

        if (checksum != packetChecksum) {
            logE(
                "DZC checksum mismatch: calculated=0x%02X expected=0x%02X"
                    .format(checksum, packetChecksum)
            )
            return
        }

        /*
         * Bytes 1..2: auxiliary sensor data.
         */
        val auxiliaryRaw =
            ((data[2].toInt() and 0xFF) shl 8) or
                (data[1].toInt() and 0xFF)

        /*
         * Bytes 3..4: weight in 0.01 kg, little-endian.
         */
        val rawWeight =
            ((data[4].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

        val weightKg = rawWeight / 100.0f

        /*
         * Bytes 5..6: impedance in 0.1 ohm, little-endian.
         */
        val rawImpedance =
            ((data[6].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)

        val impedanceOhm = rawImpedance / 10.0f

        val profileId = data[7].toInt() and 0xFF
        val status = data[8].toInt() and 0xFF
        val modeFlags = data[9].toInt() and 0xFF

        logD(
            "DZC decoded: " +
                "aux=$auxiliaryRaw, " +
                "weight=$weightKg kg, " +
                "impedance=$impedanceOhm ohm, " +
                "profile=$profileId, " +
                "status=0x%02X, ".format(status) +
                "mode=0x%02X".format(modeFlags)
        )

        /*
         * Ignore live/dynamic packets. Only save the stabilized
         * measurement to avoid duplicate entries.
         */
        if (status != STATUS_STABILIZED) {
            if (status == STATUS_DYNAMIC) {
                logD("DZC live measurement: $weightKg kg")
            } else {
                logD(
                    "Ignoring DZC packet with unknown status: " +
                        "0x%02X".format(status)
                )
            }

            return
        }

        published = true

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = weightKg
        }

        /*
         * The DZC scale supplies raw whole-body impedance but does not
         * transmit calculated body-composition metrics. Calculate them
         * using openScale's StandardImpedanceLib.
         */
        if (
            impedanceOhm >= MIN_VALID_IMPEDANCE &&
            impedanceOhm < MAX_VALID_IMPEDANCE
        ) {
            measurement.impedance = impedanceOhm.toDouble()

            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = impedanceOhm.toDouble()
            )

            measurement.fat =
                lib.totalFatPercentage.toFloat()

            measurement.water =
                lib.totalBodyWaterPercentage.toFloat()

            measurement.muscle =
                lib.skeletalMusclePercentage.toFloat()

            measurement.bone =
                lib.boneMassKg.toFloat()

            measurement.bmr =
                lib.basalMetabolicRate.toFloat()

            logI(
                "DZC body composition: " +
                    "fat=${measurement.fat}%, " +
                    "water=${measurement.water}%, " +
                    "muscle=${measurement.muscle}%, " +
                    "bone=${measurement.bone} kg"
            )
        } else {
            logD(
                "DZC impedance outside valid range: $impedanceOhm ohm"
            )
        }

        logI(
            "Publishing DZC stabilized measurement: " +
                "$weightKg kg, $impedanceOhm ohm"
        )

        publish(measurement)
        requestDisconnect()
    }
}
What changed
The important new import is:
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
And the new calculation flow is:
DZC Scale
   ↓
Weight = 85.30 kg
Impedance = 534 Ω
   ↓
StandardImpedanceLib
   ↓
Fat %
Water %
Muscle %
Bone mass
BMR
   ↓
publish()
I also added a published guard. That prevents multiple stabilized packets from creating duplicate measurements if the scale repeats its final 0x01 frame.
This implementation follows the same current openScale pattern used by VitafitVT701Handler for a scale that supplies weight plus raw impedance and relies on StandardImpedanceLib for derived body metrics. �
GitHub