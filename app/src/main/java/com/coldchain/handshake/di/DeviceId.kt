package com.coldchain.handshake.di

import javax.inject.Qualifier

/** This install's name to the server, a `String`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceId
