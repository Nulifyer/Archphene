QT += core
TEMPLATE = lib
CONFIG += plugin c++17 release
CONFIG -= app_bundle
TARGET = archphene_kde_config
SOURCES += archphenekdeconfig.cpp
INCLUDEPATH += /usr/include/KF6/KConfig /usr/include/KF6/KConfigCore
LIBS += -lKF6ConfigCore