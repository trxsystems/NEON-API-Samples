# NEON-API-Samples
Contains Sample Code that exercises the functions in the NEON Android API.

For more information and documentation about the NEON API, visit https://docs.trxsystems.com

For more information about NEON products, visit https://www.trxsystems.com


This Sample App requires an installed version of NEON Location Service and credentials for a valid subscription.

To import the complete sample applications into Android Studio, simply go to File->New->Project From Version Control->GitHub.

For the Git Repository URL - type in https://github.com/trxsystems/neon-api-samples.git. Then give the directory name for where the project will be stored on your local machine.

Hit Clone to load the sample project.

The sample will not compile without adding a Google Maps API Key, as described in Location on Google Maps. To compile, get an API Key and add the following line to res/values/strings.xml:

```xml
<string name="google_maps_key" templateMergeStrategy="preserve" translatable="false">INSERT_YOUR_API_KEY_HERE</string>
```
If you entered a valid key, google maps should load the next time you run your application.
