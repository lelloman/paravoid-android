package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.MapProperty
import org.gradle.api.GradleException
import groovy.json.JsonOutput

abstract class ParavoidLocalTriggersExtension {
    ParavoidLocalTriggersExtension() { trustedCallers.convention([:]) }
    abstract MapProperty<String,List<String>> getTrustedCallers()
    String configuration() {
        Map<String,List<String>> canonical=new TreeMap<>()
        trustedCallers.get().each { name, pins ->
            if(!(name ==~ /[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+/) || !pins ||
                pins.any { !(it instanceof String) || !(it ==~ /[0-9a-fA-F]{64}/) })
                throw new GradleException('Local triggers require package names and SHA-256 certificate fingerprints.')
            canonical[name]=pins.collect { it.toLowerCase(Locale.ROOT) }.toSorted().unique()
        }
        String json=JsonOutput.toJson(canonical)
        if(json.getBytes('UTF-8').length>16384) throw new GradleException('Local trigger configuration exceeds 16384 bytes.')
        json
    }
}
