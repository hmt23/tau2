/////////////////////////////////////////////////
//Class definintion file for the PCL_Layer class.
//
//Author:   Robert Ansell-Bell
//Created:  February 2000
//
/////////////////////////////////////////////////

#ifndef _PAPI_LAYER_H_
#define _PAPI_LAYER_H_

#ifdef TAU_PAPI
extern "C" {
#include "papiStdEventDefs.h"
#include "papi.h"
#include "papi_internal.h"
}


  struct ThreadValue{
  int ThreadID;
  int EventSet; 
  long long *CounterValues;
  };



class PapiLayer
{
  public:
  //Default getCounters.
  static long long getCounters(int tid);
};

#endif /* TAU_PAPI */
#endif /* _PAPI_LAYER_H_ */

/////////////////////////////////////////////////
//
//End PCL_Layer class definition.
//
/////////////////////////////////////////////////




