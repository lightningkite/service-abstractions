package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

@Suppress("UNCHECKED_CAST")
fun <Model : HasId<ID>, ID> KSerializer<Model>._id() =
    serializableProperties!!.find { it.name == "_id" }!! as SerializableProperty<Model, ID>

@Suppress("UNCHECKED_CAST")
fun <Model : HasEmail> KSerializer<Model>.email() =
    serializableProperties!!.find { it.name == "email" }!! as SerializableProperty<Model, String>

@Suppress("UNCHECKED_CAST")
fun <Model : HasPhoneNumber> KSerializer<Model>.phoneNumber() =
    serializableProperties!!.find { it.name == "phoneNumber" }!! as SerializableProperty<Model, String>

@JvmName("emailMaybe")
@Suppress("UNCHECKED_CAST")
fun <Model : HasMaybeEmail> KSerializer<Model>.email() =
    serializableProperties!!.find { it.name == "email" }!! as SerializableProperty<Model, String?>

@JvmName("phoneNumberMaybe")
@Suppress("UNCHECKED_CAST")
fun <Model : HasMaybePhoneNumber> KSerializer<Model>.phoneNumber() =
    serializableProperties!!.find { it.name == "phoneNumber" }!! as SerializableProperty<Model, String?>

@Suppress("UNCHECKED_CAST")
fun <Model : HasPassword> KSerializer<Model>.hashedPassword() =
    serializableProperties!!.find { it.name == "hashedPassword" }!! as SerializableProperty<Model, String>
