package com.lightningkite.services.database

public interface HasId<ID : Comparable<ID>> {
    public val _id: ID
}

public interface HasEmail {
    public val email: String
}

public interface HasPhoneNumber {
    public val phoneNumber: String
}

public interface HasMaybeEmail {
    public val email: String?
}

public interface HasMaybePhoneNumber {
    public val phoneNumber: String?
}

public interface HasPassword {
    public val hashedPassword: String
}

