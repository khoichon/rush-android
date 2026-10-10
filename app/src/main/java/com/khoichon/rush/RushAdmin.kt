package com.khoichon.rush

import android.app.admin.DeviceAdminReceiver

/** Only exists so "Lock the screen" can be a death consequence (policy: force-lock, nothing else). */
class RushAdmin : DeviceAdminReceiver()
