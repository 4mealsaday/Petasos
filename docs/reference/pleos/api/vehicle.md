# Vehicle API 이름 색인

공식 가이드 기준 2.3.2 · 수집일 2026-11-06

웹페이지가 동적으로 불러오는 공식 Dokka 본문에서 추출했다. 메서드 이름의 중복 오버로드는 하나로 묶었다. 이 색인은 검색용이며, 매개변수·nullability·권한·deprecated 여부는 연결된 클래스 원문과 사용 중인 AAR을 대조한다. 모든 SDK 타입을 수록한 목록은 아니다.

[전체 안내](../README.md) · [기계 판독 색인](symbol-index.json)

## Brake

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry)

**지원 확인:** `checkBrakePedalStatusCapabilities`

**조회:** `getCurrentBrakePedalStatus`

**구독:** `registerBrakePedalStatus`

**구독 해제:** `unregisterBrakePedalStatus`

## CarInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/entry)

**지원 확인:** `checkCRNInfoCapabilities`, `checkVINInfoCapabilities`

**조회:** `getCRNInfo`, `getVINInfo`

## Display

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/entry)

**지원 확인:** `checkTemperatureDisplayUnitCapabilities`

**조회:** `getCurrentTemperatureDisplayUnit`

**구독:** `registerTemperatureDisplayUnit`

**구독 해제:** `unregisterTemperatureDisplayUnit`

## Door

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/entry)

**지원 확인:** `checkDoorLockCapabilities`, `checkDoorPositionCapabilities`, `checkGloveBoxLockCapabilities`

**조회:** `getCurrentDoorPosition`, `getCurrentGloveBoxLockState`, `getDoorLockState`

**구독:** `registerDoorLockState`, `registerDoorPosition`, `registerGloveBoxLockState`

**설정·제어:** `setDoorLock`, `setDoorPosition`, `setGloveBoxLock`

**구독 해제:** `unregisterDoorLockState`, `unregisterDoorPosition`, `unregisterGloveBoxLockState`

## DrivingMode

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/entry)

**지원 확인:** `checkCruiseControlStateCapabilities`, `checkCruiseControlTargetSpeedCapabilities`, `checkDrivingModeStateCapabilities`

**조회:** `getCruiseControlState`, `getCruiseControlTargetSpeed`, `getCurrentDrivingModeState`

**구독:** `registerCruiseControlState`, `registerCruiseControlTargetSpeed`, `registerDrivingModeState`

**구독 해제:** `unregisterCruiseControlState`, `unregisterCruiseControlTargetSpeed`, `unregisterDrivingModeState`

## EvBattery

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry)

**지원 확인:** `checkCumulativeEvEfficiencyCapabilities`, `checkCurrentEvEfficiencyCapabilities`, `checkEvBatteryStateOfChargeCapabilities`, `checkEvChargeCurrentDrawLimitCapabilities`, `checkEvChargePercentLimitCapabilities`, `checkEvChargePortOpenCapabilities`, `checkEvChargeSwitchCapabilities`, `checkInstantaneousEvEfficiencyCapabilities`, `checkSinceChargeEvEfficiencyCapabilities`

**조회:** `getCumulativeEvEfficiency`, `getCurrentEvEfficiency`, `getEvBatteryStateOfCharge`, `getEvChargeCurrentDrawLimit`, `getEvChargeSwitchState`, `getInstantaneousEvEfficiency`, `getSinceChargeEvEfficiency`

**구독:** `registerCumulativeEvEfficiency`, `registerCurrentEvEfficiency`, `registerEvBatteryStateOfCharge`, `registerInstantaneousEvEfficiency`, `registerSinceChargeEvEfficiency`

**설정·제어:** `setEvChargeCurrentDrawLimit`, `setEvChargePercentLimit`, `setEvChargePortOpen`, `setEvChargeSwitch`

**구독 해제:** `unregisterCumulativeEvEfficiency`, `unregisterCurrentEvEfficiency`, `unregisterEvBatteryStateOfCharge`, `unregisterInstantaneousEvEfficiency`, `unregisterSinceChargeEvEfficiency`

## Fuel

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/entry)

**지원 확인:** `checkCumulativeFuelEconomyCapabilities`, `checkCurrentFuelEconomyCapabilities`, `checkFuelPercentCapabilities`, `checkInstantaneousFuelEconomyCapabilities`, `checkSinceChargeFuelEconomyCapabilities`

**조회:** `getCumulativeFuelEconomy`, `getCurrentFuelEconomy`, `getFuelPercent`, `getInstantaneousFuelEconomy`, `getSinceChargeFuelEconomy`

**구독:** `registerCumulativeFuelEconomy`, `registerCurrentFuelEconomy`, `registerFuelPercent`, `registerInstantaneousFuelEconomy`, `registerSinceChargeFuelEconomy`

**구독 해제:** `unregisterCumulativeFuelEconomy`, `unregisterCurrentFuelEconomy`, `unregisterFuelPercent`, `unregisterInstantaneousFuelEconomy`, `unregisterSinceChargeFuelEconomy`

## HVAC

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/entry)

**지원 확인:** `checkHvacAcCapabilities`, `checkHvacAirCleanerCapabilities`, `checkHvacAutoCapabilities`, `checkHvacAutoRecirculationCapabilities`, `checkHvacDefrosterCapabilities`, `checkHvacDualCapabilities`, `checkHvacFanDirectionCapabilities`, `checkHvacFanSpeedCapabilities`, `checkHvacMaxAcCapabilities`, `checkHvacMaxDefrostCapabilities`, `checkHvacPowerCapabilities`, `checkHvacRecirculationCapabilities`, `checkHvacTemperatureCapabilities`, `checkHvacTemperatureCurrentCapabilities`

**조회:** `getHvacAcState`, `getHvacAirCleanerState`, `getHvacAutoRecirculationState`, `getHvacAutoState`, `getHvacDefrosterState`, `getHvacDualState`, `getHvacFanDirection`, `getHvacFanSpeed`, `getHvacMaxAcState`, `getHvacMaxDefrostState`, `getHvacPowerState`, `getHvacRecirculationState`, `getHvacTemperature`, `getHvacTemperatureCurrent`

**구독:** `registerHvacAcState`, `registerHvacAirCleanerState`, `registerHvacAutoRecirculationState`, `registerHvacAutoState`, `registerHvacDefrosterState`, `registerHvacDualState`, `registerHvacFanSpeed`, `registerHvacMaxAcState`, `registerHvacMaxDefrostState`, `registerHvacPowerState`, `registerHvacRecirculationState`, `registerHvacTemperature`, `registerHvacTemperatureCurrent`

**설정·제어:** `setHvacAc`, `setHvacAirCleaner`, `setHvacAuto`, `setHvacAutoRecirculation`, `setHvacDefroster`, `setHvacDual`, `setHvacFanDirection`, `setHvacFanSpeed`, `setHvacMaxAc`, `setHvacMaxDefrost`, `setHvacPower`, `setHvacRecirculation`, `setHvacTemperature`

**구독 해제:** `unregisterHvacAcState`, `unregisterHvacAirCleanerState`, `unregisterHvacAutoRecirculationState`, `unregisterHvacAutoState`, `unregisterHvacDefrosterState`, `unregisterHvacDualState`, `unregisterHvacFanSpeed`, `unregisterHvacMaxAcState`, `unregisterHvacMaxDefrostState`, `unregisterHvacPowerState`, `unregisterHvacRecirculationState`, `unregisterHvacTemperature`, `unregisterHvacTemperatureCurrent`

## Light

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/entry)

**지원 확인:** `checkAmbientColorAutoByDriveModeEnabledCapabilities`, `checkAmbientLightBrightnessCapabilities`, `checkAmbientLightBrightnessColorCapabilities`, `checkAmbientLightStateCapabilities`, `checkAmbientLightSwitchCapabilities`, `checkCabinLightStateCapabilities`, `checkCabinLightSwitchCapabilities`, `checkFrontFogLightStateCapabilities`, `checkFrontFogLightSwitchCapabilities`, `checkHighBeamLightStateCapabilities`, `checkHighBeamLightSwitchCapabilities`, `checkLowBeamLightStateCapabilities`, `checkLowBeamLightSwitchCapabilities`, `checkReadingLightStateCapabilities`, `checkReadingLightSwitchCapabilities`, `checkRearFogLightStateCapabilities`, `checkRearFogLightSwitchCapabilities`, `checkTailLampLightStateCapabilities`, `checkTailLampLightSwitchCapabilities`

**조회:** `getAmbientColorAutoByDriveModeEnabled`, `getAmbientLightBrightness`, `getAmbientLightBrightnessColor`, `getAmbientLightState`, `getAmbientLightSwitch`, `getCabinLightSwitch`, `getCurrentCabinLightState`, `getCurrentFrontFogLightState`, `getCurrentHighBeamLightState`, `getCurrentLowBeamLightState`, `getCurrentReadingLightState`, `getCurrentRearFogLightState`, `getCurrentTailLampLightState`, `getFrontFogLightSwitch`, `getHighBeamLightSwitch`, `getLowBeamLightSwitch`, `getReadingLightSwitch`, `getRearFogLightSwitch`, `getTailLampLightSwitch`

**구독:** `registerAmbientColorAutoByDriveModeEnabled`, `registerAmbientLightBrightnessColorListener`, `registerAmbientLightBrightnessListener`, `registerAmbientLightStateListener`, `registerCabinLightState`, `registerFrontFogLightState`, `registerHighBeamLightState`, `registerLowBeamLightState`, `registerReadingLightState`, `registerRearFogLightState`, `registerTailLampLightState`

**설정·제어:** `setAmbientColorAutoByDriveModeEnabled`, `setAmbientLightBrightness`, `setAmbientLightBrightnessColor`, `setAmbientLightSwitch`, `setCabinLightSwitch`, `setFrontFogLightSwitch`, `setHighBeamLightSwitch`, `setLowBeamLightSwitch`, `setReadingLightSwitch`, `setRearFogLightSwitch`, `setTailLampLightSwitch`

**구독 해제:** `unregisterAmbientColorAutoByDriveModeEnabled`, `unregisterAmbientLightBrightnessColorListener`, `unregisterAmbientLightBrightnessListener`, `unregisterAmbientLightStateListener`, `unregisterCabinLightState`, `unregisterFrontFogLightState`, `unregisterHighBeamLightState`, `unregisterLowBeamLightState`, `unregisterReadingLightState`, `unregisterRearFogLightState`, `unregisterTailLampLightState`

## Odometer

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/entry)

**지원 확인:** `checkCurrentDistanceDrivenCapabilities`, `checkTripCumulativeDrivingDistanceCapabilities`, `checkTripCumulativeDrivingTimeCapabilities`, `checkTripCurrentDrivingDistanceCapabilities`, `checkTripCurrentDrivingTimeCapabilities`, `checkTripDrivingDistanceCapabilities`, `checkTripDrivingTimeCapabilities`, `checkTripInstantaneousDrivingDistanceCapabilities`, `checkTripSinceRefillDrivingDistanceCapabilities`, `checkTripSinceRefillDrivingTimeCapabilities`

**조회:** `getCurrentDistanceDriven`, `getTripCumulativeDrivingDistance`, `getTripCumulativeDrivingTime`, `getTripCurrentDrivingDistance`, `getTripCurrentDrivingTime`, `getTripInstantaneousDrivingDistance`, `getTripSinceRefillDrivingDistance`, `getTripSinceRefillDrivingTime`

**구독:** `registerDistanceDriven`, `registerTripCumulativeDrivingDistance`, `registerTripCumulativeDrivingTime`, `registerTripCurrentDrivingDistance`, `registerTripCurrentDrivingTime`, `registerTripInstantaneousDrivingDistance`, `registerTripSinceRefillDrivingDistance`, `registerTripSinceRefillDrivingTime`

**설정·제어:** `setRangeRemainingDistance`

**구독 해제:** `unregisterDistanceDriven`, `unregisterTripCumulativeDrivingDistance`, `unregisterTripCumulativeDrivingTime`, `unregisterTripCurrentDrivingDistance`, `unregisterTripCurrentDrivingTime`, `unregisterTripInstantaneousDrivingDistance`, `unregisterTripSinceRefillDrivingDistance`, `unregisterTripSinceRefillDrivingTime`

## Safety

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/entry)

**지원 확인:** `checkAirbagCapabilities`, `checkAirbagDeployedCapabilities`, `checkBrakeFluidLevelStateCapabilities`, `checkEngineOilLevelStateCapabilities`, `checkLowWasherFluidWarningStateCapabilities`, `checkPowerDownWarningIndicatorCapabilities`, `checkSeatBuckledCapabilities`, `checkSeatNotBuckledWarningCapabilities`, `checkTelltalesCapabilities`, `checkTransmissionOilLevelStateCapabilities`

**조회:** `getBrakeFluidLevelState`, `getEngineOilLevelState`, `getTelltalesInfo`, `getTransmissionOilLevelState`, `hasLowWasherFluidWarning`, `hasPowerDownWarningIndicator`, `hasSeatAirbagBeenDeployed`, `hasSeatAirbagEnabled`, `hasSeatBeltNotBuckledWarning`

**구독:** `registerBrakeFluidLevelState`, `registerEngineOilLevelState`, `registerLowWasherFluidWaringState`, `registerPowerDownWarningIndicator`, `registerSeatAirbagDeployed`, `registerSeatAirbagEnabled`, `registerSeatBeltNotBuckledWarning`, `registerTelltales`, `registerTransmissionOilLevelState`

**구독 해제:** `unregisterBrakeFluidLevelState`, `unregisterEngineOilLevelState`, `unregisterLowWasherFluidWaringState`, `unregisterPowerDownWarningIndicator`, `unregisterSeatAirbagDeployed`, `unregisterSeatAirbagEnabled`, `unregisterSeatBeltNotBuckledWarning`, `unregisterTelltales`, `unregisterTransmissionOilLevelState`

## Seat

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry)

**지원 확인:** `checkMoveSeatBackrestAngleCapabilities`, `checkMoveSeatBeltHeightCapabilities`, `checkMoveSeatDepthCapabilities`, `checkMoveSeatForeAftCapabilities`, `checkMoveSeatHeadrestAngleCapabilities`, `checkMoveSeatHeadrestForeAftCapabilities`, `checkMoveSeatHeadrestHeightCapabilities`, `checkMoveSeatHeightCapabilities`, `checkMoveSeatLumbarForeAftCapabilities`, `checkMoveSeatLumbarSideSupportCapabilities`, `checkMoveSeatTiltCapabilities`, `checkSeatBackrestAngleCapabilities`, `checkSeatBackrestAnglePositionCapabilities`, `checkSeatBeltBuckledCapabilities`, `checkSeatBeltHeightCapabilities`, `checkSeatBeltHeightPositionCapabilities`, `checkSeatDepthCapabilities`, `checkSeatDepthPositionCapabilities`, `checkSeatForeAftCapabilities`, `checkSeatForeAftPositionCapabilities`, `checkSeatHeadrestAngleCapabilities`, `checkSeatHeadrestAnglePositionCapabilities`, `checkSeatHeadrestForeAftCapabilities`, `checkSeatHeadrestForeAftPositionCapabilities`, `checkSeatHeadrestHeightCapabilities`, `checkSeatHeadrestHeightPositionCapabilities`, `checkSeatHeightCapabilities`, `checkSeatHeightPositionCapabilities`, `checkSeatLumbarForeAftCapabilities`, `checkSeatLumbarForeAftPositionCapabilities`, `checkSeatLumbarSideSupportCapabilities`, `checkSeatLumbarSideSupportPositionCapabilities`, `checkSeatMemoryCapabilities`, `checkSeatOccupancyCapabilities`, `checkSeatTemperatureCapabilities`, `checkSeatTiltCapabilities`, `checkSeatTiltPositionCapabilities`, `checkSeatVentilationCapabilities`

**조회:** `getCurrentSeatBackrestAnglePosition`, `getCurrentSeatBeltBuckledState`, `getCurrentSeatBeltHeightPosition`, `getCurrentSeatDepthPosition`, `getCurrentSeatForeAftPosition`, `getCurrentSeatHeadrestAnglePosition`, `getCurrentSeatHeadrestForeAftPosition`, `getCurrentSeatHeadrestHeightAftPosition`, `getCurrentSeatHeightPosition`, `getCurrentSeatLumbarForeAftPosition`, `getCurrentSeatLumbarSideSupportPosition`, `getCurrentSeatOccupancy`, `getCurrentSeatTiltPosition`, `getHvacSeatTemperature`, `getHvacSeatVentilationState`

**설정·제어:** `moveSeatBackrestAngle`, `moveSeatBeltHeight`, `moveSeatDepth`, `moveSeatForeAft`, `moveSeatHeadrestAngle`, `moveSeatHeadrestForeAft`, `moveSeatHeadrestHeight`, `moveSeatHeight`, `moveSeatLumbarForeAft`, `moveSeatLumbarSideSupport`, `moveSeatTilt`, `setHvacSeatTemperature`, `setHvacSeatVentilation`

**구독:** `registerHvacSeatTemperature`, `registerHvacSeatVentilation`, `registerSeatBackrestAnglePosition`, `registerSeatBeltBuckledState`, `registerSeatBeltHeightPosition`, `registerSeatDepthPosition`, `registerSeatForeAftPosition`, `registerSeatHeadrestAnglePosition`, `registerSeatHeadrestForeAftPosition`, `registerSeatHeadrestHeightAftPosition`, `registerSeatHeightPosition`, `registerSeatLumbarForeAftPosition`, `registerSeatLumbarSideSupportPosition`, `registerSeatOccupancy`, `registerSeatTiltPosition`

**수명주기·기타:** `selectSeatMemory`

**구독 해제:** `unregisterHvacSeatTemperature`, `unregisterHvacSeatVentilation`, `unregisterSeatBackrestAnglePosition`, `unregisterSeatBeltBuckledState`, `unregisterSeatBeltHeightPosition`, `unregisterSeatDepthPosition`, `unregisterSeatForeAftPosition`, `unregisterSeatHeadrestAnglePosition`, `unregisterSeatHeadrestForeAftPosition`, `unregisterSeatHeadrestHeightAftPosition`, `unregisterSeatHeightPosition`, `unregisterSeatLumbarForeAftPosition`, `unregisterSeatLumbarSideSupportPosition`, `unregisterSeatOccupancy`, `unregisterSeatTiltPosition`

## SideMirror

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/entry)

**지원 확인:** `checkHvacSideMirrorHeatCapabilities`

**조회:** `getHvacSideMirrorHeatState`

**구독:** `registerHvacSideMirrorHeatState`

**설정·제어:** `setHvacSideMirrorHeat`

**구독 해제:** `unregisterHvacSideMirrorHeatState`

## SteeringWheel

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry)

**지원 확인:** `checkHvacSteeringWheelHeatCapabilities`, `checkSteeringWheelAngleCapabilities`

**조회:** `getCurrentSteeringWheelAngle`, `getHvacSteeringWheelHeatState`

**구독:** `registerHvacSteeringWheelHeatState`, `registerSteeringWheelAngle`

**설정·제어:** `setHvacSteeringWheelHeat`

**구독 해제:** `unregisterHvacSteeringWheelHeatState`, `unregisterSteeringWheelAngle`

## Tire

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/tire/entry)

**지원 확인:** `checkLowTirePressureCapabilities`, `checkWheelAreaCapabilities`

**조회:** `getLowTirePressure`

## Toll

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/entry)

**지원 확인:** `checkEtcsVoiceGuidanceVolumeCapabilities`

**조회:** `getCurrentEtcsVoiceGuidanceVolume`

**구독:** `registerEtcsVoiceGuidanceVolume`

**설정·제어:** `setEtcsVoiceGuidanceVolume`

**구독 해제:** `unregisterEtcsVoiceGuidanceVolume`

## TurnSignal

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/entry)

**지원 확인:** `checkTurnSignalStateCapabilities`

**조회:** `getCurrentTurnSignalState`

**구독:** `registerTurnSignalState`

**구독 해제:** `unregisterTurnSignalState`

## Vehicle

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle/vehicle/entry)

**조회:** `getBrake`, `getCarInfo`, `getDisplay`, `getDoor`, `getDrivingMode`, `getEvBattery`, `getFuel`, `getHVAC`, `getLight`, `getOdometer`, `getSafety`, `getSeat`, `getSideMirror`, `getSteeringWheel`, `getTire`, `getToll`, `getTurnSignal`, `getWindow`, `getWiper`

**수명주기·기타:** `initialize`, `onFailed`, `onSelected`, `release`

## Window

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/entry)

**지원 확인:** `checkMoveWindowBlindCapabilities`, `checkSmartGlassLevelCapabilities`, `checkSmartGlassStateCapabilities`, `checkSmartGlassZoneStateCapabilities`, `checkWindowBlindPositionCapabilities`, `checkWindowLockCapabilities`, `checkWindowPositionCapabilities`

**조회:** `getCurrentSmartGlassLevel`, `getCurrentSmartGlassState`, `getCurrentWindowBlindPosition`, `getCurrentWindowLockState`, `getCurrentWindowPosition`, `getSmartGlassConfig`

**설정·제어:** `moveWindowBlind`, `setSmartGlassLevel`, `setSmartGlassState`, `setSmartGlassZoneState`, `setWindowLock`, `setWindowPosition`

**구독:** `registerSmartGlassLevel`, `registerSmartGlassState`, `registerWindowBlindPosition`, `registerWindowLockState`, `registerWindowPosition`

**구독 해제:** `unregisterSmartGlassLevel`, `unregisterSmartGlassState`, `unregisterWindowBlindPosition`, `unregisterWindowLockState`, `unregisterWindowPosition`

## Wiper

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/entry)

**지원 확인:** `checkWiperStateCapabilities`, `checkWiperSwitchCapabilities`

**조회:** `getCurrentWiperState`, `getCurrentWiperSwitch`

**구독:** `registerWiperState`

**설정·제어:** `setWiperSwitch`

**구독 해제:** `unregisterWiperState`

## WiperStateListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/wiper-state-listener/entry)

**수명주기·기타:** `onWiperStateUpdated`

