/************************************************************************************************
 * *   Plugin Testing
 * *   Tests basic functionality of a plugin for function registration event
 * *
 * *********************************************************************************************/


#include <iostream>
#include <stdio.h>
#include <stdlib.h>
#include <string>

#include <Profile/Profiler.h>
#include <Profile/TauSampling.h>
#include <Profile/TauMetrics.h>
#include <Profile/TauAPI.h>
#include <Profile/TauPlugin.h>
#include <Profile/TauSOS.h>

int Tau_plugin_dump(Tau_plugin_event_function_dump_data data) {
  printf("TAU PLUGIN: Function tid: %d\n", data.tid);
 
  TAU_SOS_send_data();
 
  return 0;
}

int Tau_plugin_finalize(Tau_plugin_event_function_finalize_data) {

  TAU_SOS_finalize();

  return 0;
}

/*This is the init function that gets invoked by the plugin mechanism inside TAU.
 * Every plugin MUST implement this function to register callbacks for various events 
 * that the plugin is interested in listening to*/
extern "C" int Tau_plugin_init_func(int argc, char **argv) {
  Tau_plugin_callbacks * cb = (Tau_plugin_callbacks*)malloc(sizeof(Tau_plugin_callbacks));

  TAU_SOS_init_simple();
  TAU_UTIL_INIT_TAU_PLUGIN_CALLBACKS(cb);
  cb->FunctionDump = Tau_plugin_dump;
  cb->FunctionFinalize = Tau_plugin_finalize;
  TAU_UTIL_PLUGIN_REGISTER_CALLBACKS(cb);

  return 0;
}

