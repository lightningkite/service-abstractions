@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

@Suppress("UNCHECKED_CAST")
public fun <Model : HasId<ID>, ID : Comparable<ID>> KSerializer<Model>._id(): Field<Model, ID> =
    this@_id.fields!!.find { it.name == "_id" }!! as Field<Model, ID>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasEmail> KSerializer<Model>.email(): Field<Model, String> =
    this@email.fields!!.find { it.name == "email" }!! as Field<Model, String>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasPhoneNumber> KSerializer<Model>.phoneNumber(): Field<Model, String> =
    this@phoneNumber.fields!!.find { it.name == "phoneNumber" }!! as Field<Model, String>

@JvmName("emailMaybe")
@Suppress("UNCHECKED_CAST")
public fun <Model : HasMaybeEmail> KSerializer<Model>.email(): Field<Model, String?> =
    this@email.fields!!.find { it.name == "email" }!! as Field<Model, String?>

@JvmName("phoneNumberMaybe")
@Suppress("UNCHECKED_CAST")
public fun <Model : HasMaybePhoneNumber> KSerializer<Model>.phoneNumber(): Field<Model, String?> =
    this@phoneNumber.fields!!.find { it.name == "phoneNumber" }!! as Field<Model, String?>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasPassword> KSerializer<Model>.hashedPassword(): Field<Model, String> =
    this@hashedPassword.fields!!.find { it.name == "hashedPassword" }!! as Field<Model, String>
