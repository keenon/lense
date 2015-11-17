#!/usr/bin/env bash

# mvn clean deploy -P release -Dmaven.test.skip=true

mvn release:clean release:prepare -Darguments="-DskipTests"
mvn release:perform -Darguments="-DskipTests"
