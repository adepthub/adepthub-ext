package org.slf4j.impl;

import logging.SbtLoggerFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/** Make SLF4J bind our ILoggerFactory implementation.
 *
 */
@SuppressWarnings("UnusedDeclaration")
public class StaticLoggerBinder implements LoggerFactoryBinder {
    public static String REQUESTED_API_VERSION = "1.7";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private final ILoggerFactory loggerFactory;

    private StaticLoggerBinder(){
        loggerFactory = new SbtLoggerFactory();
    }

    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    public String getLoggerFactoryClassStr() {
        return SbtLoggerFactory.class.getName();
    }

    public static StaticLoggerBinder getSingleton(){
        return SINGLETON;
    }
}
