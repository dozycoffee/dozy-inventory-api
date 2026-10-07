package com.dozycoffee.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class InventoryApplication

fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}
