#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#ifdef TAU_IBM_OMPT
#include <lomp/omp.h>
#endif /* TAU_IBM_OMPT */

#include "omp_collector_api.h"
#include "omp.h"
#include <stdlib.h>
#include <stdio.h> 
#include <string.h> 
#include <stdbool.h> 
#include "dlfcn.h" // for dynamic loading of symbols
#ifdef MERCURIUM_EXTRA
# define RTLD_DEFAULT   ((void *) 0)
#endif
#include "Profiler.h"
#ifdef TAU_USE_LIBUNWIND
#define UNW_LOCAL_ONLY
#include <libunwind.h>
#endif
#include "TauEnv.h"
#include <Profile/TauBfd.h>

/* An array of this struct is shared by all threads. To make sure we don't have false
 * sharing, the struct is 64 bytes in size, so that it fits exactly in
 * one (or two) cache lines. That way, when one thread updates its data
 * in the array, it won't invalidate the cache line for other threads. 
 * This is very important with timers, as all threads are entering timers
 * at the same time, and every thread will invalidate the cache line
 * otherwise. */
struct Tau_collector_status_flags {
    char idle; // 4 bytes
    char busy; // 4 bytes
    char parallel; // 4 bytes
    char ordered_region_wait; // 4 bytes
    char ordered_region; // 4 bytes
    char task_exec; // 4 bytes
    char looping; // 4 bytes
    char acquired; // 4 bytes
    char waiting; // 4 bytes
    char *timerContext; // 8 bytes(?)
    char *activeTimerContext; // 8 bytes(?)
    int *signal_message; // preallocated message for signal handling, 8 bytes
    char _pad[64-((sizeof(void*))+(2*sizeof(char*))+(9*sizeof(char)))];
};

/* This array is shared by all threads. To make sure we don't have false
 * sharing, the struct is 64 bytes in size, so that it fits exactly in
 * one (or two) cache lines. That way, when one thread updates its data
 * in the array, it won't invalidate the cache line for other threads. 
 * This is very important with timers, as all threads are entering timers
 * at the same time, and every thread will invalidate the cache line
 * otherwise. */
#if defined __INTEL__COMPILER
__declspec (align(64)) static struct Tau_collector_status_flags Tau_collector_flags[TAU_MAX_THREADS] = {0};
#elif defined __GNUC__
static struct Tau_collector_status_flags Tau_collector_flags[TAU_MAX_THREADS] __attribute__ ((aligned(64))) = {0};
#else
static struct Tau_collector_status_flags Tau_collector_flags[TAU_MAX_THREADS] = {0};
#endif

static omp_lock_t writelock;

static int Tau_collector_enabled = 1;

extern "C" void Tau_disable_collector_api() {
  // if we didn't initialize the lock, we will crash...
  if (!TauEnv_get_openmp_runtime_enabled()) return;
  //omp_set_lock(&writelock);
  Tau_collector_enabled = 0;
  //omp_unset_lock(&writelock);
}

static char* __UNKNOWN__ = "UNKNOWN";

extern const int OMP_COLLECTORAPI_HEADERSIZE;
char OMP_EVENT_NAME[35][50]= {
    "OMP_EVENT_FORK",
    "OMP_EVENT_JOIN",
    "OMP_EVENT_THR_BEGIN_IDLE",
    "OMP_EVENT_THR_END_IDLE",
    "OMP_EVENT_THR_BEGIN_IBAR",
    "OMP_EVENT_THR_END_IBAR",
    "OMP_EVENT_THR_BEGIN_EBAR",
    "OMP_EVENT_THR_END_EBAR",
    "OMP_EVENT_THR_BEGIN_LKWT",
    "OMP_EVENT_THR_END_LKWT",
    "OMP_EVENT_THR_BEGIN_CTWT",
    "OMP_EVENT_THR_END_CTWT",
    "OMP_EVENT_THR_BEGIN_ODWT",
    "OMP_EVENT_THR_END_ODWT",
    "OMP_EVENT_THR_BEGIN_MASTER",
    "OMP_EVENT_THR_END_MASTER",
    "OMP_EVENT_THR_BEGIN_SINGLE",
    "OMP_EVENT_THR_END_SINGLE",
    "OMP_EVENT_THR_BEGIN_ORDERED",
    "OMP_EVENT_THR_END_ORDERED",
    "OMP_EVENT_THR_BEGIN_ATWT",
    "OMP_EVENT_THR_END_ATWT",
    /* new events created by UH */
    "OMP_EVENT_THR_BEGIN_CREATE_TASK",
    "OMP_EVENT_THR_END_CREATE_TASK_IMM",
    "OMP_EVENT_THR_END_CREATE_TASK_DEL",
    "OMP_EVENT_THR_BEGIN_SCHD_TASK",
    "OMP_EVENT_THR_END_SCHD_TASK",
    "OMP_EVENT_THR_BEGIN_SUSPEND_TASK",
    "OMP_EVENT_THR_END_SUSPEND_TASK",
    "OMP_EVENT_THR_BEGIN_STEAL_TASK",
    "OMP_EVENT_THR_END_STEAL_TASK",
    "OMP_EVENT_THR_FETCHED_TASK",
    "OMP_EVENT_THR_BEGIN_EXEC_TASK",
    "OMP_EVENT_THR_BEGIN_FINISH_TASK",
    "OMP_EVENT_THR_END_FINISH_TASK"
};

// this is an array of state names for the OMPT interface.
// For some reason, OMPT doesn't provide a fast lookup
// for states based on the ID, so we have to make our own.
// The states are enumerated, but not consecutive. :(
// 128 should be enough, there aren't that many states.
// but the bitcodes go up to about 110.
static std::string* OMPT_STATE_NAMES[128] = {0};
static int OMPT_NUM_STATES;

const int OMP_COLLECTORAPI_HEADERSIZE=4*sizeof(int);

static int (*Tau_collector_api)(void*) = NULL;

using namespace std;

extern FunctionInfo * Tau_create_thread_state_if_necessary(const char* thread_state);
extern FunctionInfo * Tau_create_thread_state_if_necessary_string(std::string thread_state);

/*
 *-----------------------------------------------------------------------------
 * Simple hash table to map function addresses to region names/identifier
 *-----------------------------------------------------------------------------
 */

struct OmpHashNode
{
  OmpHashNode() { }

  TauBfdInfo info;		///< Filename, line number, etc.
  char * location;
};

struct OmpHashTable : public TAU_HASH_MAP<unsigned long, OmpHashNode*>
{
  OmpHashTable() { }
  virtual ~OmpHashTable() { }
};

static OmpHashTable & OmpTheHashTable()
{
  static OmpHashTable htab;
  return htab;
}

static tau_bfd_handle_t & OmpTheBfdUnitHandle()
{
  static tau_bfd_handle_t OmpbfdUnitHandle = TAU_BFD_NULL_HANDLE;
  if (OmpbfdUnitHandle == TAU_BFD_NULL_HANDLE) {
    RtsLayer::LockEnv();
    if (OmpbfdUnitHandle == TAU_BFD_NULL_HANDLE) {
      OmpbfdUnitHandle = Tau_bfd_registerUnit();
    }
    RtsLayer::UnLockEnv();
  }
  return OmpbfdUnitHandle;
}

/*
 * Get symbol table by using BFD
 */
static void OmpissueBfdWarningIfNecessary()
{
#ifndef TAU_BFD
  static bool warningIssued = false;
  if (!warningIssued) {
    fprintf(stderr,"TAU Warning: Comp_gnu - "
        "BFD is not available during TAU build. Symbols may not be resolved!\n");
    fflush(stderr);
    warningIssued = true;
  }
#endif
}

static void OmpupdateHashTable(unsigned long addr, const char *funcname)
{
  OmpHashNode * hn = OmpTheHashTable()[addr];
  if (!hn) {
    RtsLayer::LockDB();
    hn = OmpTheHashTable()[addr];
    if (!hn) {
      hn = new OmpHashNode;
      OmpTheHashTable()[addr] = hn;
    }
    RtsLayer::UnLockDB();
  }
  hn->info.funcname = funcname;
}

extern "C" char * TauInternal_CurrentCallsiteTimerName(int tid);

void Tau_get_region_id(int tid) {
    /* get the region ID */
    omp_collector_message req;
    int currentid_rsz = sizeof(long);
    int * message = (int *) calloc(OMP_COLLECTORAPI_HEADERSIZE+currentid_rsz+sizeof(int), sizeof(char));
    message[0] = OMP_COLLECTORAPI_HEADERSIZE+currentid_rsz;
    message[1] = OMP_REQ_CURRENT_PRID;
    message[2] = OMP_ERRCODE_OK;
    message[3] = currentid_rsz;
    long * rid = (long *)(message) + OMP_COLLECTORAPI_HEADERSIZE;
    int rc = (Tau_collector_api)(message);
    //TAU_VERBOSE("Thread %d, region ID : %ld\n", tid, *rid);
    free(message);
    return;
}

#ifdef TAU_UNWIND
typedef struct {
    unsigned long pc;
    int moduleIdx;
    char *name;
} Tau_collector_api_CallSiteInfo;

char * show_backtrace (int tid, int offset) {
    char * location = NULL;
    unw_cursor_t cursor; unw_context_t uc;
	memset(&cursor,0,sizeof(cursor));
	memset(&uc,0,sizeof(uc));
    unw_word_t ip, sp;

    tau_bfd_handle_t & OmpbfdUnitHandle = OmpTheBfdUnitHandle();

    unw_getcontext(&uc);
    unw_init_local(&cursor, &uc);
    int index = 0;
    static int basedepth = -1;
    int depth = basedepth + offset;

    while (unw_step(&cursor) > 0) {
        if (++index >= depth) {
            unw_get_reg(&cursor, UNW_REG_IP, &ip);
            char * newShort = NULL;
            RtsLayer::LockDB();
			OmpHashNode * node = OmpTheHashTable()[ip];
            if (!node) {
              node = new OmpHashNode;
              Tau_bfd_resolveBfdInfo(OmpbfdUnitHandle, ip, node->info);
              // Build routine name for TAU function info
              unsigned int size = strlen(node->info.funcname) + strlen(node->info.filename) + 128;
              char * routine = (char*)malloc(size);
              if (TauEnv_get_bfd_lookup()) {
                sprintf(routine, "%s [{%s} {%d,0}]", node->info.funcname, node->info.filename, node->info.lineno);
              } else {
                sprintf(routine, "[%s] UNRESOLVED %s ADDR %p", node->info.funcname, node->info.filename, (void*)ip);
              }
			  node->location = routine;
              OmpTheHashTable()[ip] = node;
            }
            RtsLayer::UnLockDB();
			TAU_VERBOSE("%d %d %d %s\n",basedepth, depth, index, node->location); fflush(stderr);
			if (basedepth == -1) {
				if (strncmp(node->info.funcname,"Tau_", 4) == 0) {  // in TAU
			    	continue; // keep unwinding
				} else if (strncmp(node->info.funcname,"addr=<0x", 8) == 0) { // in OpenMP runtime
			    	continue; // keep unwinding
				}
#if defined (TAU_OPEN64ORC)
				else if (strncmp(node->info.funcname,"__ompc_", 7) == 0) { // in OpenUH runtime
			    	continue; // keep unwinding
				}
#elif defined (__INTEL_COMPILER)
				else if (strncmp(node->info.funcname,"my_parallel_region_create", 25) == 0) { // in OMPT wraper (see below)
			    	continue; // keep unwinding
				} else if (strncmp(node->info.funcname,"__kmp", 5) == 0) { // in Intel runtime
			    	continue; // keep unwinding
				}
#elif defined(TAU_USE_OMPT) || defined(TAU_IBM_OMPT)
				else if (strncmp(node->info.funcname,"my_", 12) == 0) { // in OMPT wraper (see below)
			    	continue; // keep unwinding
				}
#else /* assume we are using gcc */
				else if (strncmp(node->info.funcname,"tau_GOMP", 8) == 0) {  // in GOMP wrapper
			    	continue; // keep unwinding
				} else if (strncmp(node->info.funcname,"__wrap_GOMP", 11) == 0) {  // in GOMP wrapper
			    	continue; // keep unwinding
				} else if (strncmp(node->info.funcname,"GOMP_", 5) == 0) {  // in GOMP runtime
			    	continue; // keep unwinding
				} else if (strncmp(node->info.funcname,"__ompc_event_callback", 21) == 0) { // in GOMP wrapper
			    	continue; // keep unwinding
				} 
#endif
				// stop unwinding
				basedepth = index;
			}
            location = (char*)malloc(strlen(node->location)+1);
            strcpy(location, node->location);
            break;
        }
    }
    return location;
}
#endif

extern "C" void Tau_get_current_region_context(int tid) {
    // Tau_get_region_id (tid);
    char * tmpStr = NULL;
#if defined(TAU_UNWIND) && defined(TAU_BFD) // need them both
    if (TauEnv_get_openmp_runtime_context() == 2) { // region
      tmpStr = show_backtrace(tid, 0); // find our source location
      if (tmpStr == NULL) {
          tmpStr = "UNKNOWN";
      }
    } else { // timer or none
      tmpStr = TauInternal_CurrentCallsiteTimerName(tid); // find our top level timer
    }
#else
    tmpStr = TauInternal_CurrentCallsiteTimerName(tid); // find our top level timer
#endif
    if (tmpStr == NULL)
        tmpStr = "UNKNOWN";
    if (Tau_collector_flags[tid].timerContext != NULL) {
	    if (strstr(tmpStr, "OpenMP_PARALLEL_REGION: ") != NULL && strlen(tmpStr) > 23) {
		    tmpStr = tmpStr=tmpStr+23;
		}
        Tau_collector_flags[tid].timerContext = (char*)realloc(Tau_collector_flags[tid].timerContext, strlen(tmpStr)+1);
    } else {
        Tau_collector_flags[tid].timerContext = (char*)malloc(strlen(tmpStr)+3);
    }
    strcpy(Tau_collector_flags[tid].timerContext, tmpStr);
    //TAU_VERBOSE("Got timer: %s\n", Tau_collector_flags[tid].timerContext);
    //TAU_VERBOSE("Forking with %d threads\n", omp_get_max_threads());
    int i;
    for (i = 0 ; i < omp_get_max_threads() ; i++) {
        if (i == tid) continue; // don't mess with yourself
        if (Tau_collector_flags[i].timerContext != NULL) {
            Tau_collector_flags[i].timerContext = (char*)realloc(Tau_collector_flags[i].timerContext, strlen(tmpStr)+3);
        } else {
            Tau_collector_flags[i].timerContext = (char*)malloc(strlen(tmpStr)+3);
        }
        strcpy(Tau_collector_flags[i].timerContext, tmpStr);
    }
    return;
}

/* We can't use unwind and BFD to get the code location, because we are
 * likely in an oulined function region, and there is no true source
 * code location. */
extern "C" void Tau_get_my_region_context(int tid, int forking) {
    // Tau_get_region_id (tid);
    char * tmpStr = NULL;
#if defined(TAU_UNWIND) && defined(TAU_BFD) // need them both
    if (TauEnv_get_openmp_runtime_context() == 2) { // region
      tmpStr = show_backtrace(tid, 1); // find our source location
      if (tmpStr == NULL) {
          tmpStr = "UNKNOWN";
      }
    } else { // timer or none
      tmpStr = TauInternal_CurrentCallsiteTimerName(tid); // find our top level timer
    }
#else
    tmpStr = TauInternal_CurrentCallsiteTimerName(tid); // find our top level timer
#endif
    if (tmpStr == NULL)
        tmpStr = "UNKNOWN";
    if (Tau_collector_flags[tid].timerContext != NULL) {
	    if (strstr(tmpStr, "OpenMP_PARALLEL_REGION: ") != NULL && strlen(tmpStr) > 23) {
		    tmpStr = tmpStr=tmpStr+23;
		}
        Tau_collector_flags[tid].timerContext = (char*)realloc(Tau_collector_flags[tid].timerContext, strlen(tmpStr)+1);
    } else {
        Tau_collector_flags[tid].timerContext = (char*)malloc(strlen(tmpStr)+1);
    }
    strcpy(Tau_collector_flags[tid].timerContext, tmpStr);
    return;
}

extern "C" void Tau_pure_start_openmp_task(const char * n, const char * t, int tid);

/*__inline*/ void Tau_omp_start_timer(const char * state, int tid, int use_context, int forking) {
  //fprintf(stderr,"%d Starting %s %d\n", tid,state,Tau_collector_flags[tid].task_exec); fflush(stderr);
  // 0 means no context wanted
  if (use_context == 0 || TauEnv_get_openmp_runtime_context() == 0) {
    //  no context for the event
    Tau_pure_start_openmp_task(state, "", tid);
  } else {
    int contextLength = 10;
#if 1
    char * regionIDstr = NULL;
    // don't do this if the worker thread is entering the parallel region - use the master's timer
    // 1 means use the timer context
    if (TauEnv_get_openmp_runtime_context() == 1 && forking == 0) {
      // use the current timer as the context
      Tau_get_my_region_context(tid, forking);
    }
    // use the current region as the context
    /* turns out the master thread wasn't updating it - so unlock and continue. */
    if (Tau_collector_flags[tid].timerContext == NULL) {
      regionIDstr = (char*)malloc(32);
    } else {
      contextLength = strlen(Tau_collector_flags[tid].timerContext);
      regionIDstr = (char*)malloc(contextLength + 32);
    }
    sprintf(regionIDstr, "%s: %s", state, Tau_collector_flags[tid].timerContext);
    // it is safe to set the active timer context now.
    if (Tau_collector_flags[tid].activeTimerContext != NULL) {
      Tau_collector_flags[tid].activeTimerContext = (char*)realloc(Tau_collector_flags[tid].activeTimerContext, contextLength+1);
    } else {
      Tau_collector_flags[tid].activeTimerContext = (char*)malloc(contextLength+1);
    }
    if (Tau_collector_flags[tid].timerContext == NULL) {
      strcpy(Tau_collector_flags[tid].activeTimerContext, "(null)");
    } else {
      strcpy(Tau_collector_flags[tid].activeTimerContext, Tau_collector_flags[tid].timerContext);
    }
    Tau_pure_start_openmp_task(regionIDstr, "", tid);
    free(regionIDstr);
#else
    // don't do this if the worker thread is entering the parallel region - use the master's timer
    // 1 means use the timer context
    if (TauEnv_get_openmp_runtime_context() == 1 && forking == 0) {
      // use the current timer as the context
      Tau_get_my_region_context(tid, forking);
    }
    // use the current region as the context
    /* turns out the master thread wasn't updating it - so unlock and continue. */
    if (Tau_collector_flags[tid].timerContext == NULL || strlen(Tau_collector_flags[tid].timerContext)==0) {
      Tau_pure_start_openmp_task(state, ": unknown", tid);
    } else {
      Tau_pure_start_openmp_task(state, Tau_collector_flags[tid].timerContext, tid);
      contextLength = strlen(Tau_collector_flags[tid].timerContext);
    }
    // it is safe to set the active timer context now.
    if (Tau_collector_flags[tid].activeTimerContext != NULL) {
      Tau_collector_flags[tid].activeTimerContext = (char*)realloc(Tau_collector_flags[tid].activeTimerContext, contextLength+1);
    } else {
      Tau_collector_flags[tid].activeTimerContext = (char*)malloc(contextLength+1);
    }
    if (Tau_collector_flags[tid].timerContext == NULL) {
      strcpy(Tau_collector_flags[tid].activeTimerContext, ": (null)");
    } else {
      strcpy(Tau_collector_flags[tid].activeTimerContext, Tau_collector_flags[tid].timerContext);
    }
#endif
  }
}

/*__inline*/ void Tau_omp_stop_timer(const char * state, int tid, int use_context) {
    //fprintf(stderr,"%d Stopping %s %d\n", tid,state,Tau_collector_flags[tid].task_exec); fflush(stderr);
    //omp_set_lock(&writelock);
    if (Tau_collector_enabled) {
      //omp_unset_lock(&writelock);
#if 1
      Tau_stop_current_timer_task(tid);
#else
      char event[256];
      sprintf(event, "%s ", state);
    Tau_pure_stop_task(event, tid);
#endif
    //} else {
      //omp_unset_lock(&writelock);
    }
}

extern "C" void Tau_omp_event_handler(OMP_COLLECTORAPI_EVENT event) {
    // THIS is here in case the very last statement in the
    // program is a parallel region - the worker threads
    // may exit AFTER thread 0 has exited, which triggered
    // the worker threads to stop all timers and dump.
    if (!Tau_collector_enabled || 
        !Tau_RtsLayer_TheEnableInstrumentation()) return;

    /* Never process anything internal to TAU */
    if (Tau_global_get_insideTAU() > 0) {
        return;
    }
    Tau_global_incr_insideTAU();

    int tid = Tau_get_tid();
    //fprintf(stderr, "** Thread: %d, (i:%d b:%d p:%d w:%d o:%d t:%d) EVENT:%s **\n", tid, Tau_collector_flags[tid].idle, Tau_collector_flags[tid].busy, Tau_collector_flags[tid].parallel, Tau_collector_flags[tid].ordered_region_wait, Tau_collector_flags[tid].ordered_region, Tau_collector_flags[tid].task_exec, OMP_EVENT_NAME[event-1]); fflush(stderr);

    switch(event) {
        case OMP_EVENT_FORK:
            Tau_get_current_region_context(tid);
            Tau_omp_start_timer("OpenMP_PARALLEL_REGION", tid, 1, 1);
            Tau_collector_flags[tid].parallel++;
            break;
        case OMP_EVENT_JOIN:
            /*
               if (Tau_collector_flags[tid].idle == 1) {
               Tau_omp_stop_timer("IDLE", tid, 0);
               Tau_collector_flags[tid].idle = 0;
               }
               */
            if (Tau_collector_flags[tid].parallel>0) {
                Tau_omp_stop_timer("OpenMP_PARALLEL_REGION", tid, 1);
                Tau_collector_flags[tid].parallel--;
            }
            break;
        case OMP_EVENT_THR_BEGIN_IDLE:
            // sometimes IDLE can be called twice in a row
            if (Tau_collector_flags[tid].idle == 1 && 
                    Tau_collector_flags[tid].busy == 0) {
                break;
            }
            if (Tau_collector_flags[tid].busy == 1) {
                Tau_omp_stop_timer("OpenMP_PARALLEL_REGION", tid, 1);
                Tau_collector_flags[tid].busy = 0;
            }
            /*
               Tau_omp_start_timer("IDLE", tid, 0, 0);
               Tau_collector_flags[tid].idle = 1;
               */
            Tau_collector_flags[tid].idle = 1;
            break;
        case OMP_EVENT_THR_END_IDLE:
            /*
               if (Tau_collector_flags[tid].idle == 1) {
               Tau_omp_stop_timer("IDLE", tid, 0);
               Tau_collector_flags[tid].idle = 0;
               }
               */
            // it is safe to set the active timer context now.
            if (Tau_collector_flags[tid].timerContext == NULL) {
                Tau_collector_flags[tid].timerContext = (char*)malloc(strlen(__UNKNOWN__)+1);
                strcpy(Tau_collector_flags[tid].timerContext, __UNKNOWN__);
            }
            if (Tau_collector_flags[tid].activeTimerContext != NULL) {
                Tau_collector_flags[tid].activeTimerContext = (char*)realloc(Tau_collector_flags[tid].activeTimerContext, strlen(Tau_collector_flags[tid].timerContext)+1);
            } else {
                Tau_collector_flags[tid].activeTimerContext = (char*)malloc(strlen(Tau_collector_flags[tid].timerContext)+1);
            }
            strcpy(Tau_collector_flags[tid].activeTimerContext, Tau_collector_flags[tid].timerContext);
            Tau_omp_start_timer("OpenMP_PARALLEL_REGION", tid, 1, 1);
            Tau_collector_flags[tid].busy = 1;
            Tau_collector_flags[tid].idle = 0;
            break;
        case OMP_EVENT_THR_BEGIN_IBAR:
            Tau_omp_start_timer("OpenMP_IMPLICIT_BARRIER", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_IBAR:
            Tau_omp_stop_timer("OpenMP_IMPLICIT_BARRIER", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_EBAR:
            Tau_omp_start_timer("OpenMP_EXPLICIT_BARRIER", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_EBAR:
            Tau_omp_stop_timer("OpenMP_EXPLICIT_BARRIER", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_LKWT:
            Tau_omp_start_timer("OpenMP_LOCK_WAIT", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_LKWT:
            Tau_omp_stop_timer("OpenMP_LOCK_WAIT", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_CTWT:
            Tau_omp_start_timer("OpenMP_CRITICAL_SECTION_WAIT", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_CTWT:
            Tau_omp_stop_timer("OpenMP_CRITICAL_SECTION_WAIT", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_ODWT:
            // for some reason, the ordered region wait is entered twice for some threads.
            if (Tau_collector_flags[tid].ordered_region_wait == 0) {
                Tau_omp_start_timer("OpenMP_ORDERED_REGION_WAIT", tid, 1, 0);
            }
            Tau_collector_flags[tid].ordered_region_wait = 1;
            break;
        case OMP_EVENT_THR_END_ODWT:
            if (Tau_collector_flags[tid].ordered_region_wait == 1) {
                Tau_omp_stop_timer("OpenMP_ORDERED_REGION_WAIT", tid, 1);
            }
            Tau_collector_flags[tid].ordered_region_wait = 0;
            break;
        case OMP_EVENT_THR_BEGIN_MASTER:
            Tau_omp_start_timer("OpenMP_MASTER_REGION", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_MASTER:
            Tau_omp_stop_timer("OpenMP_MASTER_REGION", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_SINGLE:
            Tau_omp_start_timer("OpenMP_SINGLE_REGION", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_SINGLE:
            Tau_omp_stop_timer("OpenMP_SINGLE_REGION", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_ORDERED:
            // for some reason, the ordered region is entered twice for some threads.
            if (Tau_collector_flags[tid].ordered_region == 0) {
                Tau_omp_start_timer("OpenMP_ORDERED_REGION", tid, 1, 0);
                Tau_collector_flags[tid].ordered_region = 1;
            }
            break;
        case OMP_EVENT_THR_END_ORDERED:
            if (Tau_collector_flags[tid].ordered_region == 1) {
                Tau_omp_stop_timer("OpenMP_ORDERED_REGION", tid, 1);
            }
            Tau_collector_flags[tid].ordered_region = 0;
            break;
        case OMP_EVENT_THR_BEGIN_ATWT:
            Tau_omp_start_timer("OpenMP_ATOMIC_REGION_WAIT", tid, 1, 0);
            break;
        case OMP_EVENT_THR_END_ATWT:
            Tau_omp_stop_timer("OpenMP_ATOMIC_REGION_WAIT", tid, 1);
            break;
        case OMP_EVENT_THR_BEGIN_CREATE_TASK:
            // Open64 doesn't actually create a task if there is just one thread.
            // In that case, there won't be an END_CREATE.
#if defined (TAU_OPEN64ORC)
            if (omp_get_num_threads() > 1) {
                Tau_omp_start_timer("OpenMP_CREATE_TASK", tid, 0, 0);
            }
#else
            Tau_omp_start_timer("OpenMP_CREATE_TASK", tid, 1, 0);
#endif
            break;
        case OMP_EVENT_THR_END_CREATE_TASK_IMM:
            Tau_omp_stop_timer("OpenMP_CREATE_TASK", tid, 0);
            break;
        case OMP_EVENT_THR_END_CREATE_TASK_DEL:
            Tau_omp_stop_timer("OpenMP_CREATE_TASK", tid, 0);
            break;
        case OMP_EVENT_THR_BEGIN_SCHD_TASK:
            Tau_omp_start_timer("OpenMP_SCHEDULE_TASK", tid, 0, 0);
            break;
        case OMP_EVENT_THR_END_SCHD_TASK:
            Tau_omp_stop_timer("OpenMP_SCHEDULE_TASK", tid, 0);
            break;
#if 0 // these events are somewhat unstable with OpenUH
        case OMP_EVENT_THR_BEGIN_SUSPEND_TASK:
            Tau_omp_start_timer("OpenMP_SUSPEND_TASK", tid, 0, 0);
            break;
        case OMP_EVENT_THR_END_SUSPEND_TASK:
            Tau_omp_stop_timer("OpenMP_SUSPEND_TASK", tid, 0);
            break;
        case OMP_EVENT_THR_BEGIN_STEAL_TASK:
            Tau_omp_start_timer("OpenMP_STEAL_TASK", tid, 0, 0);
            break;
        case OMP_EVENT_THR_END_STEAL_TASK:
            Tau_omp_stop_timer("OpenMP_STEAL_TASK", tid, 0);
            break;
        case OMP_EVENT_THR_FETCHED_TASK:
            break;
#endif
        case OMP_EVENT_THR_BEGIN_EXEC_TASK:
            Tau_omp_start_timer("OpenMP_EXECUTE_TASK", tid, 0, 0);
            Tau_collector_flags[tid].task_exec += 1;
            break;
        case OMP_EVENT_THR_BEGIN_FINISH_TASK:
            // When we get a "finish task", there might be a task executing...
            // or there might not.
            if (Tau_collector_flags[tid].task_exec > 0) {
                Tau_omp_stop_timer("OpenMP_EXECUTE_TASK", tid, 0);
                Tau_collector_flags[tid].task_exec -= 1;
            }
            //Tau_omp_start_timer("OpenMP_FINISH_TASK", tid, 0, 0);
            break;
        case OMP_EVENT_THR_END_FINISH_TASK:
            //Tau_omp_stop_timer("OpenMP_FINISH_TASK", tid, 0);
            break;
    }
    //TAU_VERBOSE("** Thread: %d, EVENT:%s handled. **\n", tid, OMP_EVENT_NAME[event-1]);
    //fflush(stdout);
    Tau_global_decr_insideTAU();
    return;
}

static bool initializing = false;
static bool initialized = false;

#if TAU_DISABLE_SHARED
extern int __omp_collector_api(void *);
#endif

extern "C" int Tau_initialize_collector_api(void) {
    //if (Tau_collector_api != NULL || initializing) return 0;
    if (initialized || initializing) return 0;
    if (!TauEnv_get_openmp_runtime_enabled()) {
      TAU_VERBOSE("COLLECTOR API disabled.\n"); 
      return 0;
    }

#if defined(TAU_USE_OMPT) || defined(TAU_IBM_OMPT)
    TAU_VERBOSE("COLLECTOR API disabled, using OMPT instead.\n"); 
    return 0;
#endif

    initializing = true;

    omp_init_lock(&writelock);

#if TAU_DISABLE_SHARED
	Tau_collector_api = &__omp_collector_api;
#else

#if defined (TAU_BGP) || defined (TAU_BGQ) || defined (TAU_CRAYCNL)
    // these special systems don't support dynamic symbol loading.
    *(void **) (&Tau_collector_api) = NULL;

#else

    char *error;
    *(void **) (&Tau_collector_api) = dlsym(RTLD_DEFAULT, "__omp_collector_api");
    if (Tau_collector_api == NULL) {

#if defined (__INTEL_COMPILER)
        char * libname = "libiomp5.so";
#elif defined (__GNUC__) && defined (__GNUC_MINOR__) && defined (__GNUC_PATCHLEVEL__)

#ifdef __APPLE__
        char * libname = "libgomp_g_wrap.dylib";
#else /* __APPLE__ */
        char * libname = "libTAU-gomp.so";
#endif /* __APPLE__ */

#else /* assume we are using OpenUH */
        char * libname = "libopenmp.so";
#endif /* __GNUC__ __GNUC_MINOR__ __GNUC_PATCHLEVEL__ */

        TAU_VERBOSE("Looking for library: %s\n", libname); fflush(stdout); fflush(stderr);
        void * handle = dlopen(libname, RTLD_NOW | RTLD_GLOBAL);

        if (handle != NULL) {
            TAU_VERBOSE("Looking for symbol in library: %s\n", libname); fflush(stdout); fflush(stderr);
            *(void **) (&Tau_collector_api) = dlsym(handle, "__omp_collector_api");
        }
    }
    // set this now, either it's there or it isn't.
    initialized = true;
#endif //if defined (BGL) || defined (BGP) || defined (BGQ) || defined (TAU_CRAYCNL)
    if (Tau_collector_api == NULL) {
        TAU_VERBOSE("__omp_collector_api symbol not found... collector API not enabled. \n"); fflush(stdout); fflush(stderr);
        initializing = false;
        return -1;
    }
#endif // TAU_DISABLE_SHARED
    TAU_VERBOSE("__omp_collector_api symbol found! Collector API enabled. \n"); fflush(stdout); fflush(stderr);

    omp_collector_message req;
    int rc = 0;

    /*test: check for request start, 1 message */
    int * message = (int *)malloc(OMP_COLLECTORAPI_HEADERSIZE+sizeof(int));
	memset(message, 0, OMP_COLLECTORAPI_HEADERSIZE+sizeof(int));
    message[0] = OMP_COLLECTORAPI_HEADERSIZE;
    message[1] = OMP_REQ_START;
    message[2] = OMP_ERRCODE_OK;
    message[3] = 0;
	OMP_COLLECTOR_MESSAGE *foo = (OMP_COLLECTOR_MESSAGE*)message;
	//printf("Sending message: %p, %d, %d, %d, %d\n", message, message[0], message[1], message[2], message[3]);
    rc = (Tau_collector_api)(message);
    //TAU_VERBOSE("__omp_collector_api() returned %d\n", rc); fflush(stdout); fflush(stderr);
    free(message);

    /*test for request of all events*/
    int i;
    int num_req=OMP_EVENT_THR_END_FINISH_TASK; /* last event */
    if (!TauEnv_get_openmp_runtime_events_enabled()) {
	  // if events are disabled, only do the 4 major ones
	  num_req = OMP_EVENT_THR_END_IDLE;
	}
    int register_sz = sizeof(OMP_COLLECTORAPI_EVENT) + sizeof(unsigned long *);
    int message_sz = OMP_COLLECTORAPI_HEADERSIZE + register_sz;
	//printf("Register size: %d, Message size: %d, bytes: %d\n", register_sz, message_sz, num_req*message_sz+sizeof(int));
    message = (int *) malloc(num_req*message_sz+sizeof(int));
	memset(message, 0, num_req*message_sz+sizeof(int));
	int * ptr = message;
    for(i=0;i<num_req;i++) {  
	    //printf("Ptr: %p\n", ptr);
        ptr[0] = message_sz;
        ptr[1] = OMP_REQ_REGISTER;
        ptr[2] = OMP_ERRCODE_OK;
        ptr[3] = 0;
        ptr[4] = OMP_EVENT_FORK + i;  // iterate over the events
        unsigned long * lmem = (unsigned long *)(ptr+5);
        *lmem = (unsigned long)Tau_omp_event_handler;
	    //printf("Sending message: %p, %d, %d, %d, %d, %d, %p, %p\n", ptr, ptr[0], ptr[1], ptr[2], ptr[3], ptr[4], (unsigned long *)(*(ptr+5)), ptr + message_sz); fflush(stdout);
		ptr = ptr + 7;
	    //printf("Ptr: %p\n", ptr); fflush(stdout);
    } 
    rc = (Tau_collector_api)(message);
    //TAU_VERBOSE("__omp_collector_api() returned %d\n", rc); fflush(stdout); fflush(stderr);
    free(message);

    // preallocate messages, because we can't malloc when signals are
    // handled
    //int state_rsz = sizeof(OMP_COLLECTOR_API_THR_STATE)+sizeof(unsigned long);
    int state_rsz = sizeof(OMP_COLLECTOR_API_THR_STATE);
    for(i=0;i<omp_get_max_threads();i++) {  
        Tau_collector_flags[i].signal_message = (int*)malloc(OMP_COLLECTORAPI_HEADERSIZE+state_rsz+sizeof(int));
        memset(Tau_collector_flags[i].signal_message, 0, (OMP_COLLECTORAPI_HEADERSIZE+state_rsz+sizeof(int)));
        Tau_collector_flags[i].signal_message[0] = OMP_COLLECTORAPI_HEADERSIZE+state_rsz;
        Tau_collector_flags[i].signal_message[1] = OMP_REQ_STATE;
        Tau_collector_flags[i].signal_message[2] = OMP_ERRCODE_OK;
        Tau_collector_flags[i].signal_message[3] = state_rsz;
    }

#ifdef TAU_UNWIND
    //Tau_Sampling_register_unit(); // not necessary now?
#endif

    if (TauEnv_get_openmp_runtime_states_enabled() == 1) {
    // now, for the collector API support, create the 12 OpenMP states.
    // preallocate State timers. If we create them now, we won't run into
    // malloc issues later when they are required during signal handling.
      omp_set_lock(&writelock);
      Tau_create_thread_state_if_necessary("OMP_UNKNOWN");
      Tau_create_thread_state_if_necessary("OMP_OVERHEAD");
      Tau_create_thread_state_if_necessary("OMP_WORKING");
      Tau_create_thread_state_if_necessary("OMP_IMPLICIT_BARRIER"); 
      Tau_create_thread_state_if_necessary("OMP_EXPLICIT_BARRIER");
      Tau_create_thread_state_if_necessary("OMP_IDLE");
      Tau_create_thread_state_if_necessary("OMP_SERIAL");
      Tau_create_thread_state_if_necessary("OMP_REDUCTION");
      Tau_create_thread_state_if_necessary("OMP_LOCK_WAIT");
      Tau_create_thread_state_if_necessary("OMP_CRITICAL_WAIT");
      Tau_create_thread_state_if_necessary("OMP_ORDERED_WAIT");
      Tau_create_thread_state_if_necessary("OMP_ATOMIC_WAIT");
      Tau_create_thread_state_if_necessary("OMP_TASK_CREATE");
      Tau_create_thread_state_if_necessary("OMP_TASK_SCHEDULE");
      Tau_create_thread_state_if_necessary("OMP_TASK_SUSPEND");
      Tau_create_thread_state_if_necessary("OMP_TASK_STEAL");
      Tau_create_thread_state_if_necessary("OMP_TASK_FINISH");
      omp_unset_lock(&writelock);
    }

    initializing = false;
    return 0;
}

int __attribute__ ((destructor)) Tau_finalize_collector_api(void);

int Tau_finalize_collector_api(void) {
    return 0;
#if 0
    TAU_VERBOSE("Tau_finalize_collector_api()\n");

    omp_collector_message req;
    void *message = (void *) malloc(4);   
    int *sz = (int *) message; 
    *sz = 0;
    int rc = 0;

    /*test check for request stop, 1 message */
    message = (void *) malloc(OMP_COLLECTORAPI_HEADERSIZE+sizeof(int));
    Tau_fill_header(message, OMP_COLLECTORAPI_HEADERSIZE, OMP_REQ_STOP, OMP_ERRCODE_OK, 0, 1);
    rc = (Tau_collector_api)(message);
    TAU_VERBOSE("__omp_collector_api() returned %d\n", rc);
    free(message);
#endif
}

extern "C" int Tau_get_thread_omp_state(int tid) {
    // if not available, return something useful
    if (Tau_collector_api == NULL) return -1;
    //TAU_VERBOSE("Thread %d, getting state...\n", tid);

    OMP_COLLECTOR_API_THR_STATE thread_state = THR_LAST_STATE;
    // query the thread state
	//printf("Sending message: %p, %d, %d, %d, %d\n", Tau_collector_flags[tid].signal_message, Tau_collector_flags[tid].signal_message[0], Tau_collector_flags[tid].signal_message[1], Tau_collector_flags[tid].signal_message[2], Tau_collector_flags[tid].signal_message[3]);
    (Tau_collector_api)(Tau_collector_flags[tid].signal_message);
    //OMP_COLLECTOR_API_THR_STATE * rid = (OMP_COLLECTOR_API_THR_STATE*)Tau_collector_flags[tid].signal_message + OMP_COLLECTORAPI_HEADERSIZE;
    //thread_state = *rid;
    thread_state = (OMP_COLLECTOR_API_THR_STATE)Tau_collector_flags[tid].signal_message[4];
    //TAU_VERBOSE("Thread %d, state : %d\n", tid, thread_state);
    // return the thread state as a string
    return (int)(thread_state);
}


/********************************************************
 * The functions below are for the OMPT 4.0 interface.
 * ******************************************************/

/* 
 * This header file implements a dummy tool which will execute all
 * of the implemented callbacks in the OMPT framework. When a supported
 * callback function is executed, it will print a message with some
 * relevant information.
 */

//#ifndef TAU_IBM_OMPT
#include <ompt.h>
//#endif /* TAU_IBM_OMPT */

void Tau_ompt_start_timer(const char * state, ompt_parallel_id_t regionid) {
#if 0
    char * regionIDstr = NULL;
    regionIDstr = malloc(32);
    if (regionid > 0)
      sprintf(regionIDstr, "%s %llx", state, regionid);
    else
      sprintf(regionIDstr, "%s", state);
    Tau_pure_start_task(regionIDstr, Tau_get_tid());
    free(regionIDstr);
#else
    Tau_omp_start_timer(state, Tau_get_tid(), 1, 0);
#endif
}

void Tau_ompt_stop_timer(const char * state, ompt_parallel_id_t regionid) {
#if 0
    char * regionIDstr = NULL;
    regionIDstr = malloc(32);
    sprintf(regionIDstr, "%s %llx", state, regionid);
    Tau_pure_stop_task(regionIDstr, Tau_get_tid());
    free(regionIDstr);
#else
    Tau_omp_stop_timer(state, Tau_get_tid(), 1);
#endif
}

/* These two macros make sure we don't time TAU related events */

#define TAU_OMPT_COMMON_ENTRY \
    /* Never process anything internal to TAU */ \
    if (Tau_global_get_insideTAU() > 0) { \
        /*TAU_VERBOSE("%d : %s inside TAU - returning %d\n", Tau_get_tid(), __func__, Tau_global_get_insideTAU()); */\
        return; \
    } \
    Tau_global_incr_insideTAU(); \
    int tid = Tau_get_tid(); \
    /*TAU_VERBOSE ("%d : %s inside (enter): %d\n", Tau_get_tid(), __func__, Tau_global_get_insideTAU()); \
    fflush(stdout); */

#define TAU_OMPT_COMMON_EXIT \
    Tau_global_decr_insideTAU(); \
    /* TAU_VERBOSE ("%d : %s inside (exit): %d\n\n", Tau_get_tid(), __func__, Tau_global_get_insideTAU()); */\

/*
 * Mandatory Events
 * 
 * The following events are supported by all OMPT implementations.
 */

/* Entering a parallel region */
extern "C" void my_parallel_region_create (
  ompt_data_t  *parent_task_data,   /* tool data for parent task   */
  ompt_frame_t *parent_task_frame,  /* frame data of parent task   */
  ompt_parallel_id_t parallel_id)   /* id of parallel region       */
{
  TAU_OMPT_COMMON_ENTRY;
  Tau_get_current_region_context(tid);
  Tau_omp_start_timer("OpenMP_PARALLEL_REGION", tid, 1, 1);
  //Tau_ompt_start_timer("PARALLEL_REGION", parallel_id);
  Tau_collector_flags[tid].parallel++;
  TAU_OMPT_COMMON_EXIT;
}

/* Exiting a parallel region */
void my_parallel_region_exit (
  ompt_data_t  *parent_task_data,   /* tool data for parent task   */
  ompt_frame_t *parent_task_frame,  /* frame data of parent task   */
  ompt_parallel_id_t parallel_id)   /* id of parallel region       */
{
  TAU_OMPT_COMMON_ENTRY;
  if (Tau_collector_flags[tid].parallel>0) {
    Tau_omp_stop_timer("OpenMP_PARALLEL_REGION", tid, 1);
    //Tau_ompt_stop_timer("PARALLEL_REGION", parallel_id);
    Tau_collector_flags[tid].parallel--;
  }
  TAU_OMPT_COMMON_EXIT;
}

/* Task creation */
void my_task_create (ompt_data_t *task_data) {
  TAU_OMPT_COMMON_ENTRY;
  Tau_omp_start_timer("TASK", tid, 1, 0);
  //Tau_ompt_start_timer("OpenMP_TASK", 0);
  TAU_OMPT_COMMON_EXIT;
}

/* Task exit */
void my_task_exit (ompt_data_t *task_data) {
  TAU_OMPT_COMMON_ENTRY;
  Tau_omp_stop_timer("TASK", tid, 1);
  //Tau_ompt_stop_timer("OpenMP_TASK", 0);
  TAU_OMPT_COMMON_EXIT;
}

/* Thread creation */
void my_thread_create(ompt_data_t *thread_data) {
  TAU_OMPT_COMMON_ENTRY;
  Tau_create_top_level_timer_if_necessary();
  TAU_OMPT_COMMON_EXIT;
}

/* Thread exit */
void my_thread_exit(ompt_data_t *thread_data) {
  if (!Tau_RtsLayer_TheEnableInstrumentation()) return;
  //TAU_VERBOSE("%s\n", __func__); fflush(stdout);
  TAU_OMPT_COMMON_ENTRY;
  //Tau_stop_top_level_timer_if_necessary();
  TAU_OMPT_COMMON_EXIT;
}

/* Some control event happened */
void my_control(uint64_t command, uint64_t modifier) {
  TAU_OMPT_COMMON_ENTRY;
  TAU_VERBOSE("OpenMP Control: %llx, %llx\n", command, modifier); fflush(stdout);
  // nothing to do here?
  TAU_OMPT_COMMON_EXIT;
}

extern "C" int Tau_profile_exit_all_tasks(void);

/* Shutting down the OpenMP runtime */
void my_shutdown() {
  if (!Tau_RtsLayer_TheEnableInstrumentation()) return;
  TAU_OMPT_COMMON_ENTRY;
  TAU_VERBOSE("OpenMP Shutdown.\n"); fflush(stdout);
  Tau_profile_exit_all_tasks();
  TAU_PROFILE_EXIT("exiting");
  // nothing to do here?
  TAU_OMPT_COMMON_EXIT;
}

/**********************************************************************/
/* End Mandatory Events */
/**********************************************************************/

/**********************************************************************/
/* Macros for common wait, acquire, release functionality. */
/**********************************************************************/

#define TAU_OMPT_WAIT_ACQUIRE_RELEASE(WAIT_FUNC,ACQUIRED_FUNC,RELEASE_FUNC,WAIT_NAME,REGION_NAME) \
void WAIT_FUNC (ompt_wait_id_t *waitid) { \
  TAU_OMPT_COMMON_ENTRY; \
  Tau_ompt_start_timer(WAIT_NAME, 0); \
  Tau_collector_flags[tid].waiting = 1; \
  TAU_OMPT_COMMON_EXIT; \
} \
 \
void ACQUIRED_FUNC (ompt_wait_id_t *waitid) { \
  TAU_OMPT_COMMON_ENTRY; \
  if (Tau_collector_flags[tid].waiting>0) { \
    Tau_ompt_stop_timer(WAIT_NAME, 0); \
  } \
  Tau_collector_flags[tid].waiting = 0; \
  Tau_ompt_start_timer(REGION_NAME, 0); \
  Tau_collector_flags[tid].acquired = 1; \
  TAU_OMPT_COMMON_EXIT; \
} \
 \
void RELEASE_FUNC (ompt_wait_id_t *waitid) { \
  TAU_OMPT_COMMON_ENTRY; \
  if (Tau_collector_flags[tid].acquired>0) { \
    Tau_ompt_stop_timer(REGION_NAME, 0); \
  } \
  Tau_collector_flags[tid].acquired = 0; \
  TAU_OMPT_COMMON_EXIT; \
} \

TAU_OMPT_WAIT_ACQUIRE_RELEASE(my_wait_atomic,my_acquired_atomic,my_release_atomic,"OpenMP_ATOMIC_REGION_WAIT","OpenMP_ATOMIC_REGION")
TAU_OMPT_WAIT_ACQUIRE_RELEASE(my_wait_ordered,my_acquired_ordered,my_release_ordered,"OpenMP_ORDERED_REGION_WAIT","OpenMP_ORDERED_REGION")
TAU_OMPT_WAIT_ACQUIRE_RELEASE(my_wait_critical,my_acquired_critical,my_release_critical,"OpenMP_CRITICAL_REGION_WAIT","OpenMP_CRITICAL_REGION")
TAU_OMPT_WAIT_ACQUIRE_RELEASE(my_wait_lock,my_acquired_lock,my_release_lock,"OpenMP_LOCK_WAIT","OpenMP_LOCK")

#undef TAU_OMPT_WAIT_ACQUIRE_RELEASE

/**********************************************************************/
/* Macros for common begin / end functionality. */
/**********************************************************************/

#define TAU_OMPT_SIMPLE_BEGIN_AND_END(BEGIN_FUNCTION,END_FUNCTION,NAME) \
void BEGIN_FUNCTION (ompt_data_t  *parent_task_data, ompt_parallel_id_t parallel_id) { \
  TAU_OMPT_COMMON_ENTRY; \
  /*Tau_ompt_start_timer(NAME, parallel_id); */ \
  Tau_omp_start_timer(NAME, tid, 1, 0); \
  TAU_OMPT_COMMON_EXIT; \
} \
\
void END_FUNCTION (ompt_data_t  *parent_task_data, ompt_parallel_id_t parallel_id) { \
  TAU_OMPT_COMMON_ENTRY; \
  /*Tau_ompt_stop_timer(NAME, parallel_id); */ \
  Tau_omp_stop_timer(NAME, tid, 0); \
  TAU_OMPT_COMMON_EXIT; \
}

#define TAU_OMPT_LOOP_BEGIN_AND_END(BEGIN_FUNCTION,END_FUNCTION,NAME) \
void BEGIN_FUNCTION (ompt_data_t  *parent_task_data, ompt_parallel_id_t parallel_id) { \
  TAU_OMPT_COMMON_ENTRY; \
  /*Tau_ompt_start_timer(NAME, parallel_id); */ \
  Tau_omp_start_timer(NAME, tid, 1, 0); \
  Tau_collector_flags[tid].looping=1; \
  TAU_OMPT_COMMON_EXIT; \
} \
\
void END_FUNCTION (ompt_data_t  *parent_task_data, ompt_parallel_id_t parallel_id) { \
  TAU_OMPT_COMMON_ENTRY; \
  /*Tau_ompt_stop_timer(NAME, parallel_id); */ \
  if (Tau_collector_flags[tid].looping==1) { \
  Tau_omp_stop_timer(NAME, tid, 0); } \
  Tau_collector_flags[tid].looping=0; \
  TAU_OMPT_COMMON_EXIT; \
}

TAU_OMPT_SIMPLE_BEGIN_AND_END(my_barrier_begin,my_barrier_end,"OpenMP_BARRIER")
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_wait_barrier_begin,my_wait_barrier_end,"OpenMP_WAIT_BARRIER")
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_master_begin,my_master_end,"OpenMP_MASTER_REGION")
TAU_OMPT_LOOP_BEGIN_AND_END(my_loop_begin,my_loop_end,"OpenMP_LOOP")
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_section_begin,my_section_end,"OpenMP_SECTION") 
//TAU_OMPT_SIMPLE_BEGIN_AND_END(my_single_in_block_begin,my_single_in_block_end,"OpenMP_SINGLE_IN_BLOCK") 
//TAU_OMPT_SIMPLE_BEGIN_AND_END(my_single_others_begin,my_single_others_end,"OpenMP_SINGLE_OTHERS") 
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_taskwait_begin,my_taskwait_end,"OpenMP_TASKWAIT") 
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_wait_taskwait_begin,my_wait_taskwait_end,"OpenMP_WAIT_TASKWAIT") 
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_taskgroup_begin,my_taskgroup_end,"OpenMP_TASKGROUP") 
TAU_OMPT_SIMPLE_BEGIN_AND_END(my_wait_taskgroup_begin,my_wait_taskgroup_end,"OpenMP_WAIT_TASKGROUP") 

#undef TAU_OMPT_SIMPLE_BEGIN_AND_END

/**********************************************************************/
/* Specialized begin / end functionality. */
/**********************************************************************/

/* Thread end idle */
void my_idle_end(ompt_data_t *thread_data) {
  if (!Tau_RtsLayer_TheEnableInstrumentation()) return;
  TAU_OMPT_COMMON_ENTRY;
  Tau_omp_stop_timer("IDLE", tid, 0);
  // if this thread is not the master of a team, then assume this 
  // thread is entering a new parallel region
#if 1
  if (Tau_collector_flags[tid].parallel==0) {
    if (Tau_collector_flags[tid].activeTimerContext != NULL) {
        free(Tau_collector_flags[tid].activeTimerContext);
    }
    if (Tau_collector_flags[tid].timerContext == NULL) {
        Tau_collector_flags[tid].timerContext = (char*)malloc(strlen(__UNKNOWN__)+1);
        strcpy(Tau_collector_flags[tid].timerContext, __UNKNOWN__);
    }
    Tau_collector_flags[tid].activeTimerContext = (char*)malloc(strlen(Tau_collector_flags[tid].timerContext)+1);
    strcpy(Tau_collector_flags[tid].activeTimerContext, Tau_collector_flags[tid].timerContext);
    Tau_omp_start_timer("OpenMP_PARALLEL_REGION", tid, 1, 1);
    Tau_collector_flags[tid].busy = 1;
  }
  Tau_collector_flags[tid].idle = 0;
#endif
  TAU_OMPT_COMMON_EXIT;
}

/* Thread begin idle */
void my_idle_begin(ompt_data_t *thread_data) {
  TAU_OMPT_COMMON_ENTRY;
  // if this thread is not the master of a team, then assume this 
  // thread is exiting a parallel region
#if 1
  if (Tau_collector_flags[tid].parallel==0) {
    if (Tau_collector_flags[tid].idle == 1 && 
        Tau_collector_flags[tid].busy == 0) {
        TAU_OMPT_COMMON_EXIT;
        return;
    }
    if (Tau_collector_flags[tid].busy == 1) {
        Tau_omp_stop_timer("OpenMP_PARALLEL_REGION", tid, 1);
        Tau_collector_flags[tid].busy = 0;
    }
  }
  Tau_collector_flags[tid].idle = 1;
#endif
  Tau_omp_start_timer("IDLE", tid, 0, 0);
  TAU_OMPT_COMMON_EXIT;
}

#undef TAU_OMPT_COMMON_ENTRY
#undef TAU_OMPT_COMMON_EXIT

//#ifdef TAU_IBM_OMPT
//#define CHECK(EVENT,FUNCTION,NAME) ompt_set_callback(EVENT, FUNCTION)
//#else 
#define CHECK(EVENT,FUNCTION,NAME) \
  TAU_VERBOSE("Registering OMPT callback %s!\n",NAME); \
  fflush(stderr); \
  if (ompt_set_callback(EVENT, (ompt_callback_t)(FUNCTION)) == 0) { \
    TAU_VERBOSE("Failed to register OMPT callback %s!\n",NAME); \
    fflush(stderr); \
  }
//#endif /* TAU_IBM_OMPT */

int ompt_initialize() {
  Tau_init_initializeTAU();
  if (initialized || initializing) return 0;
  if (!TauEnv_get_openmp_runtime_enabled()) return 0;
  TAU_VERBOSE("Registering OMPT events...\n"); fflush(stderr);
  initializing = true;
  omp_init_lock(&writelock);

  /* required events */
  CHECK(ompt_event_parallel_create, my_parallel_region_create, "parallel_create");
  CHECK(ompt_event_parallel_exit, my_parallel_region_exit, "parallel_exit");
#ifndef TAU_IBM_OMPT
  // IBM will call task_create, but not task_exit. :(
  CHECK(ompt_event_task_create, my_task_create, "task_create");
  CHECK(ompt_event_task_exit, my_task_exit, "task_exit");
#endif
//#ifndef TAU_IBM_OMPT
  CHECK(ompt_event_thread_create, my_thread_create, "thread_create");
//#endif
  CHECK(ompt_event_thread_exit, my_thread_exit, "thread_exit");
  CHECK(ompt_event_control, my_control, "event_control");
#ifndef TAU_IBM_OMPT
  CHECK(ompt_event_runtime_shutdown, my_shutdown, "runtime_shutdown");
#endif /* TAU_IBM_OMPT */

  if (TauEnv_get_openmp_runtime_events_enabled()) {
  /* optional events, "blameshifting" */
#ifndef TAU_IBM_OMPT 
  // actually, don't do the idle event at all for now
  //CHECK(ompt_event_idle_begin, my_idle_begin, "idle_begin");
  //CHECK(ompt_event_idle_end, my_idle_end, "idle_end");
  
  // IBM will call wait_barrier_begin, but not wait_barrier_end. :(
  CHECK(ompt_event_wait_barrier_begin, my_wait_barrier_begin, "wait_barrier_begin");
  CHECK(ompt_event_wait_barrier_end, my_wait_barrier_end, "wait_barrier_end");
#endif
  CHECK(ompt_event_wait_taskwait_begin, my_wait_taskwait_begin, "wait_taskwait_begin");
  CHECK(ompt_event_wait_taskwait_end, my_wait_taskwait_end, "wait_taskwait_end");
  CHECK(ompt_event_wait_taskgroup_begin, my_wait_taskgroup_begin, "wait_taskgroup_begin");
  CHECK(ompt_event_wait_taskgroup_end, my_wait_taskgroup_end, "wait_taskgroup_end");
  CHECK(ompt_event_release_lock, my_release_lock, "release_lock");
//ompt_event(ompt_event_release_nest_lock_last, ompt_wait_callback_t, 18, ompt_event_release_nest_lock_implem
  CHECK(ompt_event_release_critical, my_release_critical, "release_critical");
  CHECK(ompt_event_release_atomic, my_release_atomic, "release_atomic");
  CHECK(ompt_event_release_ordered, my_release_ordered, "release_ordered");

  /* optional events, synchronous events */
#ifndef TAU_IBM_OMPT
  // IBM will call task_create, but not task_exit. :(
  CHECK(ompt_event_implicit_task_create, my_task_create, "task_create");
  CHECK(ompt_event_implicit_task_exit, my_task_exit, "task_exit");
#endif
  CHECK(ompt_event_barrier_begin, my_barrier_begin, "barrier_begin");
  CHECK(ompt_event_barrier_end, my_barrier_end, "barrier_end");
  CHECK(ompt_event_master_begin, my_master_begin, "master_begin");
  CHECK(ompt_event_master_end, my_master_end, "master_end");
//ompt_event(ompt_event_task_switch, ompt_task_switch_callback_t, 24, ompt_event_task_switch_implemented) /* 
  CHECK(ompt_event_loop_begin, my_loop_begin, "loop_begin");
  CHECK(ompt_event_loop_end, my_loop_end, "loop_end");
  CHECK(ompt_event_section_begin, my_section_begin, "section_begin");
  CHECK(ompt_event_section_end, my_section_end, "section_end");
/* When using Intel, there are times when the non-single thread continues on its
 * merry way. For now, don't track the time spent in the "other" threads. 
 * We have no way of knowing when the other threads finish waiting, because for
 * Intel they don't wait - they just continue. */
  //CHECK(ompt_event_single_in_block_begin, my_single_in_block_begin, "single_in_block_begin");
  //CHECK(ompt_event_single_in_block_end, my_single_in_block_end, "single_in_block_end");
  //CHECK(ompt_event_single_others_begin, my_single_others_begin, "single_others_begin");
  //CHECK(ompt_event_single_others_end, my_single_others_end, "single_others_end");
  CHECK(ompt_event_taskwait_begin, my_taskwait_begin, "taskwait_begin");
  CHECK(ompt_event_taskwait_end, my_taskwait_end, "taskwait_end");
  CHECK(ompt_event_taskgroup_begin, my_taskgroup_begin, "taskgroup_begin");
  CHECK(ompt_event_taskgroup_end, my_taskgroup_end, "taskgroup_end");

//ompt_event(ompt_event_release_nest_lock_prev, ompt_parallel_callback_t, 41, ompt_event_release_nest_lock_pr

  CHECK(ompt_event_wait_lock, my_wait_lock, "wait_lock");
//ompt_event(ompt_event_wait_nest_lock, ompt_wait_callback_t, 43, ompt_event_wait_nest_lock_implemented) /* n
  CHECK(ompt_event_wait_critical, my_wait_critical, "wait_critical");
  CHECK(ompt_event_wait_atomic, my_wait_atomic, "wait_atomic");
  CHECK(ompt_event_wait_ordered, my_wait_ordered, "wait_ordered");

  CHECK(ompt_event_acquired_lock, my_acquired_lock, "acquired_lock");
//ompt_event(ompt_event_acquired_nest_lock_first, ompt_wait_callback_t, 48, ompt_event_acquired_nest_lock_fir
//ompt_event(ompt_event_acquired_nest_lock_next, ompt_parallel_callback_t, 49, ompt_event_acquired_nest_lock_
  CHECK(ompt_event_acquired_critical, my_acquired_critical, "acquired_critical");
  CHECK(ompt_event_acquired_atomic, my_acquired_atomic, "acquired_atomic");
  CHECK(ompt_event_acquired_ordered, my_acquired_ordered, "acquired_ordered");

//ompt_event(ompt_event_init_lock, ompt_wait_callback_t, 53, ompt_event_init_lock_implemented) /* lock init *
//ompt_event(ompt_event_init_nest_lock, ompt_wait_callback_t, 54, ompt_event_init_nest_lock_implemented) /* n
//ompt_event(ompt_event_destroy_lock, ompt_wait_callback_t, 55, ompt_event_destroy_lock_implemented) /* lock 
//ompt_event(ompt_event_destroy_nest_lock, ompt_wait_callback_t, 56, ompt_event_destroy_nest_lock_implemented

//ompt_event(ompt_event_flush, ompt_thread_callback_t, 57, ompt_event_flush_implemented) /* after executing f
  }
  TAU_VERBOSE("OMPT events registered! \n"); fflush(stderr);

#if defined(TAU_USE_OMPT) || defined(TAU_IBM_OMPT)
// make the states
  if (TauEnv_get_openmp_runtime_states_enabled() == 1) {
    // now, for the collector API support, create the OpenMP states.
    // preallocate State timers. If we create them now, we won't run into
    // malloc issues later when they are required during signal handling.
    int current_state = ompt_state_work_serial;
    int next_state = 0;
    const char *next_state_name;
    std::string *next_state_name_string;
	std::string *serial = new std::string("ompt_state_work_serial");
    OMPT_STATE_NAMES[ompt_state_work_serial] = serial;
    Tau_create_thread_state_if_necessary("ompt_state_work_serial");
    while (ompt_enumerate_state(current_state, &next_state, &next_state_name) == 1) {
      TAU_VERBOSE("Got state %d: '%s'\n", next_state, next_state_name);
      if (next_state >= 128) {
        TAU_VERBOSE("WARNING! MORE OMPT STATES THAN EXPECTED! PROGRAM COULD CRASH!!!\n");
      }
	  next_state_name_string = new std::string(next_state_name);
      OMPT_STATE_NAMES[next_state] = next_state_name_string;
      Tau_create_thread_state_if_necessary(next_state_name);
      current_state = next_state;
    }
    // next_state now holds our max 
  }
  TAU_VERBOSE("OMPT states registered! \n"); fflush(stderr);
#endif

  initializing = false;
  initialized = true;

  return 1;
}

#if defined(TAU_USE_OMPT) || defined(TAU_IBM_OMPT)
std::string * Tau_get_thread_ompt_state(int tid) {
    // if not available, return something useful
    if (!initialized) return NULL;
    //TAU_VERBOSE("Thread %d, getting state...\n", tid);
    // query the thread state
    ompt_wait_id_t wait;
    ompt_state_t state = ompt_get_state(&wait);
    //TAU_VERBOSE("Thread %d, state : %d\n", tid, state);
    // return the thread state as a string
    return OMPT_STATE_NAMES[state];
}
#endif

/* THESE ARE OTHER WEAK IMPLEMENTATIONS, IN CASE OMPT SUPPORT IS NONEXISTENT */

/* initialization */
#ifndef TAU_USE_OMPT
extern __attribute__ (( weak ))
  int ompt_set_callback(ompt_event_t evid, ompt_callback_t cb) { return -1; };
#endif

/* THESE ARE OTHER WEAK IMPLEMENTATIONS, IN CASE COLLECTOR API SUPPORT IS NONEXISTENT */
#if !defined (TAU_OPEN64ORC)
#if defined __GNUC__
extern __attribute__ ((weak))
  int __omp_collector_api(void *message) { TAU_VERBOSE ("Error linking GOMP wrapper. Try using tau_exec with the -gomp option.\n"); return -1; };
#endif
#endif
