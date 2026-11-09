# Naver map-sdk 3.24.0's optional FusedLocationSource$b is the only SDK class
# referencing this missing type: requestLocationUpdates/removeLocationUpdates
# return Task and immediately discard it. The POM intentionally omits Play
# Services; the SDK consumer rules already cover its optional location/common APIs.
# Petasos never constructs FusedLocationSource and always leaves locationSource=null.
# The diagnostic -dontshrink retains this otherwise unused helper. Suppress only
# this exact missing class, not any tasks/package wildcard or executable SDK code.
# Before using that helper, remove this rule and add the official location dependency.
-dontwarn com.google.android.gms.tasks.Task
