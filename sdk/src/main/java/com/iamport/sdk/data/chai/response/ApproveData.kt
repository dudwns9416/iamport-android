package com.iamport.sdk.data.chai.response

data class ApproveData(
    val impUid: String,
    val merchantUid: String,
    val success: String,
    val reason: String?,
)