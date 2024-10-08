//
// Copyright (c) 2023 ZettaScale Technology
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License 2.0 which is available at
// http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//
// Contributors:
//   ZettaScale Zenoh Team, <zenoh@zettascale.tech>
//

package io.zenoh.selector

import io.zenoh.keyexpr.KeyExpr
import java.net.URLDecoder

/**
 * A selector is the combination of a [KeyExpr], which defines the
 * set of keys that are relevant to an operation, and a [parameters], a set of key-value pairs with a few uses:
 *
 *  * specifying arguments to a queryable, allowing the passing of Remote Procedure Call parameters
 *  * filtering by value,
 *  * filtering by metadata, such as the timestamp of a value
 *
 * @property keyExpr The [KeyExpr] of the selector.
 * @property parameters The parameters of the selector.
 */
class Selector(val keyExpr: KeyExpr, val parameters: String? = null) : AutoCloseable {

    /**
     * If the [parameters] argument is defined, this function extracts its name-value pairs into a map,
     * returning an error in case of duplicated parameters.
     */
    fun parametersStringMap(): Result<Map<String, String>>? {
        return parameters?.let {
            it.split('&').fold<String, Map<String, String>>(mapOf()) { parametersMap, parameter ->
                val keyValuePair = parameter.split('=')
                val key = keyValuePair[0]
                if (parametersMap.containsKey(key)) {
                    throw IllegalArgumentException("Duplicated parameter `$key` detected.")
                }
                val value = keyValuePair.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) } ?: ""
                parametersMap + (key to value)
            }.let { map -> Result.success(map) }
        }
    }

    override fun toString(): String {
        return parameters?.let { "$keyExpr?$parameters" } ?: keyExpr.toString()
    }

    /** Closes the selector's [KeyExpr]. */
    override fun close() {
        keyExpr.close()
    }
}
