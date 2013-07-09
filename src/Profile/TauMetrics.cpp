/****************************************************************************
**                      TAU Portable Profiling Package                     **
**                      http://www.cs.uoregon.edu/research/tau             **
*****************************************************************************
**    Copyright 2009                                                       **
**    Department of Computer and Information Science, University of Oregon **
**    Advanced Computing Laboratory, Los Alamos National Laboratory        **
**    Forschungszentrum Juelich                                            **
****************************************************************************/
/****************************************************************************
**      File            : TauMetrics.cpp                                   **
**      Description     : TAU Profiling Package                            **
**      Contact         : tau-bugs@cs.uoregon.edu                          **
**      Documentation   : See http://www.cs.uoregon.edu/research/tau       **
**                                                                         **
**      Description     : Handles Metrics                                  **
**                                                                         **
****************************************************************************/

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <tau_internal.h>
#include <Profile/Profiler.h>
#include <Profile/TauTrace.h>
#ifdef CUPTI
#include <Profile/CuptiLayer.h>
#endif //CUPTI

#ifdef TAUKTAU_SHCTR
#include "Profile/KtauCounters.h"
#endif //TAUKTAU_SHCTR

using namespace std;
using namespace tau;

void metric_read_nullClock(int tid, int idx, double values[]);
void metric_write_userClock(int tid, double value);
void metric_read_userClock(int tid, int idx, double values[]);
void metric_read_logicalClock(int tid, int idx, double values[]);
void metric_read_gettimeofday(int tid, int idx, double values[]);
void metric_read_clock_gettime(int tid, int idx, double values[]);
void metric_read_linuxtimers(int tid, int idx, double values[]);
void metric_read_bgtimers(int tid, int idx, double values[]);
void metric_read_craytimers(int tid, int idx, double values[]);
void metric_read_cputime(int tid, int idx, double values[]);
void metric_read_messagesize(int tid, int idx, double values[]);
void metric_read_papivirtual(int tid, int idx, double values[]);
void metric_read_papiwallclock(int tid, int idx, double values[]);
void metric_read_papi(int tid, int idx, double values[]);
void metric_read_ktau(int tid, int idx, double values[]);
void metric_read_cudatime(int tid, int idx, double values[]);
void metric_read_cupti(int tid, int idx, double values[]);


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


int TauMetrics_init();
int Tau_init_check_initialized(void); 

static void metricv_add(const char *name);
static void read_env_vars();
static void initialize_functionArray();

#ifndef TAU_MAX_METRICS
#define TAU_MAX_METRICS 25
#endif
/* Global Variable holding the number of counters */
int Tau_Global_numCounters = -1;

static TauUserEvent **traceCounterEvents;

typedef void (*function)(int, int, double[]);

static char *metricv[TAU_MAX_METRICS];
static int nmetrics = 0;

/* nfunctions can be different from nmetrics because
   a single call to PAPI can provide several metrics */
static int nfunctions = 0;

/* traceMetric in the index used for the trace metric (might not be zero) */
static int traceMetric = 0;

/* array of function pointers used to get metric data */
static function functionArray[TAU_MAX_METRICS];

/* gtod based initial timestamp, used for snapshots and other stuff */
static x_uint64 initialTimeStamp;

/* flags for atomic metrics */
char *TauMetrics_atomicMetrics[TAU_MAX_METRICS] = {NULL};

/*********************************************************************
 * Remove _'s, convert case, and compare
 * This function also changes 'one' to match 'two' if they fuzzy match correctly
 * Returns 1 if they match, 0 otherwise
 ********************************************************************/
static int compareMetricString(char *one, const char *two) {
  char m1[512], m2[512];
  char *p;
  int i;

  strcpy(m1, one);
  strcpy(m2, two);
  while ((p = strchr(m1, '_')) != NULL) {
    while (*p) {
      *p = *(p + 1);
      p++;
    }
  }
  while ((p = strchr(m2, '_')) != NULL) {
    while (*p) {
      *p = *(p + 1);
      p++;
    }
  }
  for (i = 0; m1[i]; i++) {
    m1[i] = toupper(m1[i]);
  }
  for (i = 0; m2[i]; i++) {
    m2[i] = toupper(m2[i]);
  }

  if (strcmp(m1, m2) == 0) {
    /* overwrite the matching name */
    strcpy(one, two);
    return 1;
  } else {
    return 0;
  }
}

/*********************************************************************
 * Add a metric to the metrics vector
 ********************************************************************/
static void metricv_add(const char *name) {
  int i;
  if (nmetrics >= TAU_MAX_METRICS) {
    fprintf(stderr, "Number of counters exceeds TAU_MAX_METRICS (%d), please reconfigure TAU with -useropt=-DTAU_MAX_METRICS=<higher number>.\n", TAU_MAX_METRICS);
 		exit(1); 
	} else {
    for (i = 0; i < nmetrics; i++) {
      if (compareMetricString(metricv[i], name)) {
        return;
      }
    }
    metricv[nmetrics] = strdup(name);
    nmetrics++;
  }
}

/*********************************************************************
 * This routine will reorder the metrics so that the PAPI ones all come last
 * Note: traceMetric must already be set
 ********************************************************************/
static void reorder_metrics(const char *match) {
  const char *newMetricV[TAU_MAX_METRICS];
  int idx = 0;
  int newTraceMetric;

  for (int i = 0; i < nmetrics; i++) {
    if (strncmp(match, metricv[i], strlen(match)) != 0) {
      newMetricV[idx++] = metricv[i];
    }
  }

  for (int i = 0; i < nmetrics; i++) {
    if (strncmp(match, metricv[i], strlen(match)) == 0) {
      newMetricV[idx++] = metricv[i];
    }
  }

  for (int i = 0; i < nmetrics; i++) {
    if (strcmp(newMetricV[i], metricv[traceMetric]) == 0) {
      newTraceMetric = i;
    }
  }

  for (int i = 0; i < nmetrics; i++) {
    metricv[i] = strdup(newMetricV[i]);
  }

  traceMetric = newTraceMetric;
}

/*********************************************************************
 * Read the configuration entries into the metric vector
 ********************************************************************/
static void read_env_vars() {
  const char *token;
  const char *taumetrics = TauEnv_get_metrics();
  char *ptr, *ptr2;

  if (taumetrics && strlen(taumetrics) == 0) {
    taumetrics = NULL;
  }

  if (taumetrics) {
    char *metrics = strdup(taumetrics);
    for (ptr = metrics; *ptr; ptr++) {
      if (*ptr == '\\') {
        /* escaped, skip over */
        for (ptr2 = ptr; *(ptr2); ptr2++) {
          *ptr2 = *(ptr2 + 1);
        }
        ptr++;
      } else {
        if (*ptr == ':') {
          *ptr = ',';
        }
      }
    }

    token = strtok(metrics, ",");
    while (token) {
      metricv_add(token);
      token = strtok(NULL, ",");
    }
  } else {
    char counterName[256];
    for (int i = 1; i < 26; i++) {
      sprintf(counterName, "COUNTER%d", i);
      char *metric = getenv(counterName);
      if (metric && strlen(metric) == 0) {
        metric = NULL;
      }
      if (metric) {
        metricv_add(metric);
      }
    }

    if (nmetrics == 0) {
#if defined(BGL_TIMERS)
      metricv_add("BGL_TIMERS");
#elif defined(BGP_TIMERS)
      metricv_add("BGP_TIMERS");
#elif defined(BGQ_TIMERS)
      metricv_add("BGQ_TIMERS");
#else
      // NOTE: Probably need more #elif defined() for other platforms...
      metricv_add("TIME");
#endif
    }
  }
}

/*********************************************************************
 * Initialize KTAU metrics
 ********************************************************************/
static void TauMetrics_initializeKTAU() {
#ifdef TAUKTAU_SHCTR

  for (int i = 0; i < nmetrics; i++) {
    int cType = 0;
    if (strncmp("KTAU", metricv[i], 4) == 0) {
      if (strstr(metricv[i], "KTAU_INCL_") != NULL) {
        cType = KTAU_SHCTR_TYPE_INCL;
      } else if (strstr(metricv[i], "KTAU_NUM_") != NULL) {
        cType = KTAU_SHCTR_TYPE_NUM;
      } else {
        cType = KTAU_SHCTR_TYPE_EXCL;
      }
      char *metric = strdup(metricv[i]);
      metric = metric + 5; /* strip "KTAU_" */
      KtauCounters::addCounter(metric, cType);
    }
  }

#endif
}

/*********************************************************************
 * Query if a string is a PAPI metric
 ********************************************************************/
static int is_papi_metric(char *str) {
  if (strncmp("PAPI", str, 4) == 0) {
    if (compareMetricString(str, "PAPI_TIME") == 0
        && compareMetricString(str, "PAPI_VIRTUAL_TIME") == 0) {
      return 1;
    }
  }
  return 0;
}

#ifdef CUPTI
/*********************************************************************
 * Query if a string is a CUPTI metric
 ********************************************************************/
static int is_cupti_metric(char *str) {
  if (strncmp("CUDA", str, 4) == 0) {
		if (Tau_CuptiLayer_is_cupti_counter(str))
		{
			return 1;
		}
  }
  return 0;
}
#endif //CUPTI

/*********************************************************************
 * Initialize the function array
 ********************************************************************/
static void initialize_functionArray() 
{
  int usingPAPI = 0;
  int pos = 0;
  int found = 0;
  int ktau = 0;
#ifdef TAUKTAU_SHCTR
  ktau = 1;
#endif

  int papi_available = 0;
#ifdef TAU_PAPI
  papi_available = 1;
#endif

  for (int i = 0; i < nmetrics; i++) {
    found = 1;
    if (compareMetricString(metricv[i], "LOGICAL_CLOCK")) {
      functionArray[pos++] = metric_read_logicalClock;
    } else if (compareMetricString(metricv[i], "USER_CLOCK")) {
      functionArray[pos++] = metric_read_userClock;
    } else if (compareMetricString(metricv[i], "GET_TIME_OF_DAY")) {
      functionArray[pos++] = metric_read_gettimeofday;
    } else if (compareMetricString(metricv[i], "CLOCK_GET_TIME")) {
      functionArray[pos++] = metric_read_clock_gettime;
    } else if (compareMetricString(metricv[i], "TIME")) {
      functionArray[pos++] = metric_read_gettimeofday;
    } else if (compareMetricString(metricv[i], "CPU_TIME")) {
      functionArray[pos++] = metric_read_cputime;
#ifdef TAU_LINUX_TIMERS
    } else if (compareMetricString(metricv[i], "LINUX_TIMERS")) {
      functionArray[pos++] = metric_read_linuxtimers;
#endif
    } else if (compareMetricString(metricv[i], "BGL_TIMERS")) {
      functionArray[pos++] = metric_read_bgtimers;
    } else if (compareMetricString(metricv[i], "BGP_TIMERS")) {
      functionArray[pos++] = metric_read_bgtimers;
    } else if (compareMetricString(metricv[i], "BGQ_TIMERS")) {
      functionArray[pos++] = metric_read_bgtimers;
    } else if (compareMetricString(metricv[i], "CRAY_TIMERS")) {
      functionArray[pos++] = metric_read_craytimers;
    } else if (compareMetricString(metricv[i], "TAU_MPI_MESSAGE_SIZE")) {
      functionArray[pos++] = metric_read_messagesize;
#ifdef CUPTI
		} else if (is_cupti_metric(metricv[i])) {
			/* CUPTI handled separately */
			/* setup CUPTI metrics */
			//functionArray[pos++] = metric_read_cupti;
			Tau_CuptiLayer_register_string(metricv[i], pos);
      TauMetrics_atomicMetrics[pos] = metricv[i];
      functionArray[pos++] = metric_read_cupti;
#endif //CUPTI
#ifdef TAU_PAPI
    } else if (compareMetricString(metricv[i], "P_WALL_CLOCK_TIME")) {
      usingPAPI = 1;
      functionArray[pos++] = metric_read_papiwallclock;
    } else if (compareMetricString(metricv[i], "PAPI_TIME")) {
      usingPAPI = 1;
      functionArray[pos++] = metric_read_papiwallclock;
    } else if (compareMetricString(metricv[i], "P_VIRTUAL_TIME")) {
      usingPAPI = 1;
      functionArray[pos++] = metric_read_papivirtual;
    } else if (compareMetricString(metricv[i], "PAPI_VIRTUAL_TIME")) {
      usingPAPI = 1;
      functionArray[pos++] = metric_read_papivirtual;
#endif /* TAU_PAPI */
    } else if (compareMetricString(metricv[i], "TAUGPU_TIME")) {
      functionArray[pos++] = metric_read_cudatime;
    } else {
      if (papi_available && is_papi_metric(metricv[i])) {
        /* PAPI handled separately */
      } else if (ktau && strncmp("KTAU", metricv[i], 4) == 0) {
        /* KTAU handled separately */
      } else {
        fprintf(stderr, "TAU: Error: Unknown metric: %s\n", metricv[i]);

        /* Delete the metric */
        for (int j = i; j < nmetrics - 1; j++) {
          metricv[j] = metricv[j + 1];
        }
        nmetrics--;
        i--;
        found = 0;

        /* old: null clock
           functionArray[pos++] = metric_read_nullClock;
         */
      }
    }
    if (found) {
      TAU_VERBOSE("TAU: Using metric: %s\n", metricv[i]);
    }
  }

  /* check if we are using PAPI */
  for (int i = 0; i < nmetrics; i++) {
    if (is_papi_metric(metricv[i])) {
      functionArray[pos++] = metric_read_papi;
      usingPAPI = 1;
      break;
    }
	}

#ifdef TAUKTAU_SHCTR
  for (int i = 0; i < nmetrics; i++) {
    if (strncmp("KTAU", metricv[i], 4) == 0) {
      functionArray[pos++] = metric_read_ktau;
      break;
    }
  }
  TauMetrics_initializeKTAU();
#endif

#ifdef TAU_PAPI
  if (usingPAPI) {
    PapiLayer::initializePapiLayer();
  }
#endif
	

  for (int i = 0; i < nmetrics; i++) {
    if (is_papi_metric(metricv[i])) {
      if (strstr(metricv[i], "PAPI") != NULL) {
        char *metricString = strdup(metricv[i]);

        if (strstr(metricString, "NATIVE") != NULL) {
          /* Fix the name for a native event */
          int idx = 0;
          while (metricString[12 + idx] != '\0') {
            metricString[idx] = metricString[12 + idx];
            idx++;
          }
          metricString[idx] = '\0';
        }

#ifdef TAU_PAPI
        int counterID = PapiLayer::addCounter(metricString);
        if (counterID == -1) {
          /* Delete the metric */
          for (int j = i; j < nmetrics - 1; j++) {
            metricv[j] = metricv[j + 1];
          }
          nmetrics--;
        }
#endif
        free(metricString);
      }
    }
  }

  nfunctions = pos;
}

/*********************************************************************
 * Returns metric name for an index
 ********************************************************************/
extern "C" const char *TauMetrics_getMetricName(int metric) {
#ifdef CUPTI
  if (Tau_CuptiLayer_is_cupti_counter(metricv[metric]) && metric < Tau_CuptiLayer_get_num_events())
  {
      return Tau_CuptiLayer_get_event_name(Tau_CuptiLayer_get_cupti_event_id(metric));
  }
  else {
#endif
    return metricv[metric];
#ifdef CUPTI
  }
#endif
}

/*********************************************************************
 * Query if a metric is used
 ********************************************************************/
int TauMetrics_getMetricUsed(int metric) {
  if (metric < nmetrics) {
    return 1;
  } else {
    return 0;
  }
}

/*********************************************************************
 * Query if a metric is atomic
 ********************************************************************/
const char* TauMetrics_getMetricAtomic(int metric) {
  return TauMetrics_atomicMetrics[metric];
}

/*********************************************************************
 * Read the metrics
 ********************************************************************/
extern "C"  bool TauCompensateInitialized(void);
void TauMetrics_getMetrics(int tid, double values[]) 
{
  if (Tau_init_check_initialized()) {
      for (int i = 0; i < nfunctions; i++) {
        functionArray[i](tid, i, values);
      }
  } else {
    // *CWL* - Safe only if Compensation is safely initialized. Otherwise
    //         we would be in the middle of re-entrant behavior and
    //         would be re-initializing metrics each time.
    if (TauCompensateInitialized()) {
      TauMetrics_init();
    }
  }
}

extern "C" void TauMetrics_internal_alwaysSafeToGetMetrics(int tid, double values[]) {
  for (int i = 0; i < nfunctions; i++) {
    functionArray[i](tid, i, values);
  }
}


extern "C" x_uint64 TauMetrics_getInitialTimeStamp() {
  return initialTimeStamp;
}


extern "C" x_uint64 TauMetrics_getTimeOfDay() {
  x_uint64 timestamp;
#ifdef TAU_WINDOWS
  timestamp = TauWindowsUsecD();
#else
  struct timeval tp;
  gettimeofday (&tp, 0);
  timestamp = (x_uint64)tp.tv_sec * (x_uint64)1e6 + (x_uint64)tp.tv_usec;
#endif
  return timestamp;
}

/*********************************************************************
 * Initialize the metrics module
 ********************************************************************/
int TauMetrics_init() {
  int i;

  initialTimeStamp = TauMetrics_getTimeOfDay();

  if (TauEnv_get_ebs_enabled()) {
    // *CWL* - keep an eye on this. *must* we do this? Or can we
    //         keep PAPI overflow signal triggers separate from
    //         user-selected metrics to be measured.
    if (strcmp(TauEnv_get_ebs_source(),"itimer")!=0) {
      metricv_add(TauEnv_get_ebs_source());
    }
  }

  /* Set the user clock values to 0 */
  for (i = 0; i < TAU_MAX_THREADS; i++) {
    metric_write_userClock(i, 0);
  }

  read_env_vars();

  traceMetric = 0;
  reorder_metrics("PAPI\0");
  reorder_metrics("KTAU\0");

  initialize_functionArray();

  Tau_Global_numCounters = nmetrics;

  /* Create atomic events for tracing */
  if (TauEnv_get_tracing()) {
    traceCounterEvents = new TauUserEvent *[nmetrics];
    /* We obtain the timestamp from COUNTER1, so we only need to trigger
       COUNTER2-N or i=1 through no. of active functions not through 0 */
    for (i = 1; i < nmetrics; i++) {
      traceCounterEvents[i] = new TauUserEvent(metricv[i], true);
      /* the second arg is MonotonicallyIncreasing which is true (HW counters)*/
    }
  }

  return 0;
}

/*********************************************************************
 * Trigger atomic events for each metric
 ********************************************************************/
void TauMetrics_triggerAtomicEvents(unsigned long long timestamp, double *values, int tid) {
  int i;
#ifndef TAU_EPILOG
  for (i = 1; i < nmetrics; i++) {
    TauTraceEvent(traceCounterEvents[i]->GetId(), (long long)values[i], tid, timestamp, 1);
    // 1 in the last parameter is for use timestamp
  }
#endif /* TAU_EPILOG */
}

/*********************************************************************
 * Returns a duplicated list of counter names, and writes the number
 * of counters in numCounters
 ********************************************************************/
void TauMetrics_getCounterList(const char ***counterNames, int *numCounters) {
  *numCounters = nmetrics;
  *counterNames = (char const **)malloc(sizeof(char *) * nmetrics);
  for (int i = 0; i < nmetrics; i++) {
    (*counterNames)[i] = strdup(TauMetrics_getMetricName(i));
  }
}

/*********************************************************************
 * Returns the index of the trace metric
 ********************************************************************/
double TauMetrics_getTraceMetricIndex() {
  return traceMetric;
}

/*********************************************************************
 * Returns the value of the trace metric
 ********************************************************************/
double TauMetrics_getTraceMetricValue(int tid) {
  double values[TAU_MAX_COUNTERS];
  TauMetrics_getMetrics(tid, values);
  return values[traceMetric];
}

/**********************************************************************
 * Returns the index in the metric vector of a particular metric string
 *    *CWL* Intended for EBS to resolve the index location of the metric
 *    source at data output time. This assumes the metric vector does
 *    not change between the time it is read and when it is used. This
 *    is not true, I believe, so we need to keep an eye on this.
 *    There are also other issues of name matching ... EBS (this ought
 *    to be changed) uses "itimer" instead of "TIME". So, we are going
 *    to do the unethical practice of assuming it is TIME if there is
 *    a failure to match the presented name against the names in the
 *    metric vector.
 **********************************************************************/
int TauMetrics_getMetricIndexFromName(const char *metricString) {
  for (int i=0; i<nmetrics; i++) {
    if (compareMetricString(metricv[i], metricString) == 1) {
      return i;
    }
  }
  /* EBS ONLY:
     Try again assuming TIME. The reason we loop again is because in the
     general case, TIME isn't necessarily in position 0. */
  if (TauEnv_get_ebs_enabled()) {
    for (int i=0; i<nmetrics; i++) {
      if (compareMetricString(metricv[i], "TIME") == 1) {
	return i;
      }
    }
  }
  /* This is a bad failure value. EBS cannot handle this. Other features
     might. */
  return -1;
}


#ifdef __cplusplus
}
#endif /* __cplusplus */
