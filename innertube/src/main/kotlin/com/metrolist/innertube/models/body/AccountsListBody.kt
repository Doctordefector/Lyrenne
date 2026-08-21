package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class AccountsListBody(
    val context: Context,
    val requestType: String = "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER",
    val callCircumstance: String = "SWITCHING_USERS_FULL",
)
