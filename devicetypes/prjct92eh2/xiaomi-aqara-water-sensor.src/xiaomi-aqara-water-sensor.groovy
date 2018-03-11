/*
 *  Copyright 2016 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus


metadata {
	definition(name: "Xiaomi Aqara Water Sensor", namespace: "prjct92eh2", author: "prjct92eh2") {
		capability "Configuration"
		capability "Battery"
		capability "Refresh"
		capability "Water Sensor"
		capability "Health Check"
		capability "Sensor"

   		attribute "lastCheckin", "String"

		command "enrollResponse"


		/*fingerprint inClusters: "0000,0001,0003,0402,0500,0020,0B05", outClusters: "0019", manufacturer: "CentraLite", model: "3315-S", deviceJoinName: "Water Leak Sensor"
		fingerprint inClusters: "0000,0001,0003,0020,0402,0500,0B05", outClusters: "0019", manufacturer: "CentraLite", model: "3315-L", deviceJoinName: "Iris Smart Water Sensor"
		fingerprint inClusters: "0000,0001,0003,000F,0020,0402,0500", outClusters: "0019", manufacturer: "SmartThings", model: "moisturev4", deviceJoinName: "Water Leak Sensor"*/
        fingerprint inClusters: "0000,0001,0003", outClusters: "0019", manufacturer: "LUMI", model: "lumi.sensor_wleak.aq1", deviceJoinName: "Xiaomi Water Sensor"
	}

	simulator {

	}

	preferences {
		section {
			image(name: 'educationalcontent', multiple: true, images: [
					"http://cdn.device-gse.smartthings.com/Moisture/Moisture1.png",
					"http://cdn.device-gse.smartthings.com/Moisture/Moisture2.png",
					"http://cdn.device-gse.smartthings.com/Moisture/Moisture3.png"
			])
		}
	}

	tiles(scale: 2) {
		multiAttributeTile(name: "water", type: "generic", width: 6, height: 4) {
			tileAttribute("device.water", key: "PRIMARY_CONTROL") {
				attributeState "dry", label: "Dry", icon: "st.alarm.water.dry", backgroundColor: "#ffffff"
				attributeState "wet", label: "Wet", icon: "st.alarm.water.wet", backgroundColor: "#00A0DC"
			}
                tileAttribute("device.lastCheckin", key: "SECONDARY_CONTROL") {
    				attributeState("default", label:'Last Update: ${currentValue}',icon: "st.Health & Wellness.health9")
            }
		}
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label: '${currentValue}% battery', unit: ""
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
		}

		main(["water", "battery"])
		details(["water", "battery", "refresh"])
	}
}

def parse(String description) {
	log.debug "description: $description"

    //  send event for heartbeat    
    def now = new Date().format("yyyy MMM dd EEE h:mm:ss a", location.timeZone)
    sendEvent(name: "lastCheckin", value: now)

	// getEvent will handle temperature and humidity
	Map map = [:]
	if (description?.startsWith('catchall:')) {
      			map = parseCatchAllMessage(description)
		} else { 	
        	if (description?.startsWith('zone status')) {
			map = parseIasMessage(description)
			} else {
				Map descMap = zigbee.parseDescriptionAsMap(description)
				if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
					map = getBatteryResult(Integer.parseInt(descMap.value, 16))
				} else if (descMap?.clusterInt == zigbee.IAS_ZONE_CLUSTER && descMap.attrInt == zigbee.ATTRIBUTE_IAS_ZONE_STATUS && descMap?.value) {
					map = translateZoneStatus(new ZoneStatus(zigbee.convertToInt(descMap?.value)))
				} 
			}
        }
    
    log.debug "Parse returned $map"
	def result = map ? createEvent(map) : [:]

	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
	return result
}

private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)

	translateZoneStatus(zs)
}

private Map translateZoneStatus(ZoneStatus zs) {
	return zs.isAlarm1Set() ? getMoistureResult('wet') : getMoistureResult('dry')
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def linkText = getLinkText(device)
    log.debug '${linkText} Battery'

	log.debug rawValue

	def result = [
		name: 'battery',
		value: '--'
	]
    
	def volts = Math.round(rawValue * 100 / 255)
    def maxVolts = 100

	if (volts > maxVolts) {
				volts = maxVolts
    }
   
    result.value = volts
	result.descriptionText = "${device.displayName} battery was ${result.value}%"

	return result
}

private Map getMoistureResult(value) {
	log.debug "water"
	def descriptionText
	if (value == "wet")
		descriptionText = '{{ device.displayName }} is wet'
	else
		descriptionText = '{{ device.displayName }} is dry'
	return [
			name           : 'water',
			value          : value,
			descriptionText: descriptionText,
			translatable   : true
	]
}

private Map parseCatchAllMessage(String description) {
    def linkText = getLinkText(device)
	Map resultMap = [:]
	def cluster = zigbee.parse(description)
	log.debug cluster
	if (cluster) {
		switch(cluster.clusterId) {
			case 0x0000:
			resultMap = getBatteryResult(cluster.data.get(6))
			break

			case 0xFC02:
			log.debug '${linkText}: ACCELERATION'
			break

			case 0x0402:
			log.debug '${linkText}: TEMP'
				// temp is last 2 data values. reverse to swap endian
				String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
				def value = getTemperature(temp)
				resultMap = getTemperatureResult(value)
				break
		}
	}

	return resultMap
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return readAttribute(0x0000, 0x001, 0x0020) // Read the Battery Level
}

def refresh() {
	log.debug "Refreshing Battery"
	def refreshCmds = zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
			zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
			zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS)
	
    zigbee.configureReporting(0x0001, 0x0021, 0x20, 300, 600, 0x01)
    
	return refreshCmds + zigbee.enrollResponse()
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	// enrolls with default periodic reporting until newer 5 min interval is confirmed
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])

	// temperature minReportTime 30 seconds, maxReportTime 5 min. Reporting interval if no activity
	// battery minReport 30 seconds, maxReportTime 6 hrs by default
	return refresh() + zigbee.batteryConfig() //+ zigbee.temperatureConfig(30, 300) //send refresh cmds as part of config
}
