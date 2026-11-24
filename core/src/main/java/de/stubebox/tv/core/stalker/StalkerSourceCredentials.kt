package de.stubebox.tv.core.stalker

import de.stubebox.tv.core.database.entity.SourceEntity

/** One authoritative mapping so every sync/playback re-auth path carries the same device identity. */
fun SourceEntity.stalkerCredentials(canonicalMac: String): StalkerCredentials = StalkerCredentials(
    sourceId = id,
    portalUrl = url,
    mac = canonicalMac,
    userAgent = userAgent,
    deviceIdentity = StalkerDeviceIdentity(
        serialNumber = stalkerSerialNumber,
        deviceId = stalkerDeviceId,
        deviceId2 = stalkerDeviceId2,
        signature = stalkerSignature,
    ),
    login = username,
    password = password,
)
