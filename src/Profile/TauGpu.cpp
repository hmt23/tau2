/****************************************************************************
**                      TAU Portable Profiling Package                     **
**                      http://www.cs.uoregon.edu/research/paracomp/tau    **
*****************************************************************************
**    Copyright 2010                                                       **
**    Department of Computer and Information Science, University of Oregon **
**    Advanced Computing Laboratory                                        **
****************************************************************************/
/***************************************************************************
**      File            : TauGpu.cpp                                      **
**      Description     : TAU trace format reader library header files    **
**      Author          : Shangkar Mayanglambam                           **
**                      : Scott Biersdorff                                **
**      Contact         : scottb@cs.uoregon.edu                           **
***************************************************************************/

#include "TauGpu.h"
#include "TAU.h"
#include <TauInit.h>
#include <stdio.h>
#include <iostream>
#include <iomanip>
#include <Profile/OpenMPLayer.h>

void *main_ptr, *gpu_ptr;

//TAU_PROFILER_REGISTER_EVENT(MemoryCopyEventHtoD, "Bytes copied from Host to Device");
//TAU_PROFILER_REGISTER_EVENT(MemoryCopyEventDtoH, "Bytes copied from Device to Host");

static TauContextUserEvent *MemoryCopyEventHtoD;
static TauContextUserEvent *MemoryCopyEventDtoH;
static TauContextUserEvent *MemoryCopyEventDtoD;

int number_of_tasks = 0;
int number_of_top_level_task_events = 0;

bool gpuComp(gpuId* a, gpuId* b)
{
	return not a->equals(b);
}

map<gpuId*, int, bool(*)(gpuId*,gpuId*)>& TheGpuIdMap(void)
{
	bool (*gpuCompFunc)(gpuId*, gpuId*) = gpuComp;
	static map<gpuId*, int, bool(*)(gpuId*, gpuId*)> GpuIdMap(gpuCompFunc);

	return GpuIdMap;
}

//The number of Memcpys called with unknown transfer size which should be given
//on the GPU thread.
int counted_memcpys = 0;

#include <linux/unistd.h>

extern "C" void metric_set_gpu_timestamp(int tid, double value);
extern "C" void Tau_set_thread_fake(int tid);

#include<map>
using namespace std;

double cpu_start_time;
/*
struct EventName {
		const char *name;
		EventName(const char* n) :
			name(n) {}	
		bool operator<(const EventName &c1) const { return strcmp(name,c1.name) < 0; }
};
*/
//typedef map<eventId, bool> doubleMap;
//doubleMap MemcpyEventMap;

//map<EventName, void*> events;


void check_gpu_event(int gpuTask)
{
	if (number_of_top_level_task_events < number_of_tasks)
	{
#ifdef DEBUG_PROF
		cerr << "first gpu event" << endl;
#endif
		TAU_PROFILER_START_TASK(gpu_ptr, gpuTask);
		number_of_top_level_task_events++;
	}
}

/* === Begin implementing the hooks === */

/* create TAU callback routine to capture both CPU and GPU execution time 
	takes the thread id as a argument. */

void Tau_gpu_enter_event(const char* name)
{
#ifdef DEBUG_PROF
	printf("entering cu event: %s.\n", name);
#endif
	TAU_START(name);
}
void Tau_gpu_enter_memcpy_event(const char *functionName, gpuId
*device, int transferSize, int memcpyType)
{
#ifdef DEBUG_PROF
	//printf("entering cuMemcpy event: %s.\n", name);
#endif

	if (strcmp(functionName, TAU_GPU_USE_DEFAULT_NAME) == 0)
	{
		if (memcpyType == MemcpyHtoD) {
			functionName = "Memory copy Host to Device";
		}
		else if (memcpyType == MemcpyDtoH)
		{
			functionName = "Memory copy Device to Host";
		}
		else 
		{
			functionName = "Memory copy (Other)";
		}
		//printf("using default name: %s.\n", functionName);
	}

	TAU_START(functionName);

	//Inorder to capture the entire memcpy transaction time start the send/recived
	//at the start of the event
	if (memcpyType == MemcpyDtoH) {
		TauTraceOneSidedMsg(MESSAGE_RECV, device, -1, 0);
	}
	else
	{
		TauTraceOneSidedMsg(MESSAGE_SEND, device, transferSize, 0);
	}

	if (memcpyType == MemcpyHtoD) {
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			TAU_CONTEXT_EVENT(MemoryCopyEventHtoD, transferSize);
			//TAU_EVENT(MemoryCopyEventHtoD(), transferSize);
		}
		else
		{
			counted_memcpys--;
		}
	}
	else if (memcpyType == MemcpyDtoH)
	{
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			TAU_CONTEXT_EVENT(MemoryCopyEventDtoH, transferSize);
			//TAU_EVENT(MemoryCopyEventDtoH(), transferSize);
		}
		else
		{
			counted_memcpys--;
		}
	}
  else
	{
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			TAU_CONTEXT_EVENT(MemoryCopyEventDtoD, transferSize);
		}
		else
		{
			counted_memcpys--;
		}
	}
	
}
void Tau_gpu_exit_memcpy_event(const char * functionName, gpuId *device, int
memcpyType)
{
#ifdef DEBUG_PROF
	//printf("exiting cuMemcpy event: %s.\n", name);
#endif

	if (strcmp(functionName, TAU_GPU_USE_DEFAULT_NAME) == 0)
	{
		if (memcpyType == MemcpyHtoD) {
			functionName = "Memory copy Host to Device";
		}
		else if (memcpyType == MemcpyDtoH)
		{
			functionName = "Memory copy Device to Host";
		}
		else
		{
			functionName = "Memory copy (Other)";
		}
		//printf("using default name: %s.\n", functionName);
	}

	// Place the Message into the trace in when the memcpy in exited if this
	// thread receives the message otherwise do it when this event is entered.
	// This is too make the message lines in the trace to always point forward in
	// time.

	TAU_STOP(functionName);

}

void Tau_gpu_exit_event(const char *name)
{
#ifdef DEBUG_PROF
	printf("exit cu event: %s.\n", name);
#endif
	TAU_STOP(name);
	if (strcmp(name, "cuCtxDetach") == 0)
	{
		//We're done with the gpu, stop the top level timer.
#ifdef DEBUG_PROF
		printf("in cuCtxDetach.\n");
#endif
		//TAU_PROFILER_STOP_TASK(gpu_ptr, gpuTask);
		//TAU_PROFILER_STOP(main_ptr);
	}
}
void start_gpu_event(const char *name, int gpuTask)
{
#ifdef DEBUG_PROF
	printf("staring %s event.\n", name);
#endif
	/*
	map<EventName, void*>::iterator it = events.find(name);
	if (it == events.end())
	{
		void *ptr;
		TAU_PROFILER_CREATE(ptr, name, "", TAU_USER);
		TAU_PROFILER_START_TASK(ptr, gpuTask);
		events[EventName(name)] = ptr;
	} else
	{
		void *ptr = (*it).second;
		TAU_PROFILER_START_TASK(ptr, gpuTask);
	}
	*/
	TAU_START_TASK(name, gpuTask);
}
void stage_gpu_event(const char *name, int gpuTask, double start_time,
FunctionInfo* parent)
{
#ifdef DEBUG_PROF
	cout << "setting gpu timestamp for start " <<  setprecision(16) << start_time << endl;
#endif
	metric_set_gpu_timestamp(gpuTask, start_time);

	check_gpu_event(gpuTask);
	if (TauEnv_get_callpath()) {
  	//printf("Profiler: %s \n", parent->GetName());
		if (parent != NULL) {
			Tau_start_timer(parent, 0, gpuTask);
		}
	}
	start_gpu_event(name, gpuTask);
}
void stop_gpu_event(const char *name, int gpuTask)
{
#ifdef DEBUG_PROF
	printf("stopping %s event.\n", name);
#endif
/*
	map<EventName,void*>::iterator it = events.find(name);
	if (it == events.end())
	{
		printf("FATAL ERROR in stopping GPU event.\n");
	} else
	{
		void *ptr = (*it).second;
		TAU_PROFILER_STOP_TASK(ptr, gpuTask);
	}
*/
	TAU_STOP_TASK(name, gpuTask);
}
void break_gpu_event(const char *name, int gpuTask, double stop_time,
FunctionInfo* parent)
{
#ifdef DEBUG_PROF
	cout << "setting gpu timestamp for stop: " <<  setprecision(16) << stop_time << endl;
#endif
	metric_set_gpu_timestamp(gpuTask, stop_time);
	stop_gpu_event(name, gpuTask);
	if (TauEnv_get_callpath()) {
  	//printf("Profiler: %s \n", parent->GetName());
		double totalTime = 0;
		if (parent != NULL) {
			Tau_stop_timer(parent, gpuTask);
		}
	}	
}
int get_task(gpuId *new_task)
{
	int task = 0;
	map<gpuId*, int>::iterator it = TheGpuIdMap().find(new_task);
	if (it == TheGpuIdMap().end())
	{
		gpuId *create_task = new_task->getCopy();
		task = TheGpuIdMap()[create_task] = Tau_RtsLayer_createThread();
		number_of_tasks++;
		Tau_set_thread_fake(task);
		//TAU_CREATE_TASK(task);
		//printf("new task: %s id: %d.\n", new_task->printId(), task);
	} else
	{
		task = (*it).second;
	}

	return task;
}

eventId Tau_gpu_create_gpu_event(const char *name, gpuId *device,
FunctionInfo* callingSite, TauGpuContextMap* map)
{
	return eventId(name, device, callingSite, map);
}

eventId Tau_gpu_create_gpu_event(const char *name, gpuId *device,
FunctionInfo* callingSite)
{
	return eventId(name, device, callingSite, NULL);
}

void Tau_gpu_register_gpu_event(eventId id, double startTime, double endTime)
{
	//printf("Tau gpu name: %s.\n", id.name);
	int task = get_task(id.device);
  
	//printf("in TauGpu.cpp, registering gpu event.\n");
	//printf("Tau gpu name: %s.\n", name);
	stage_gpu_event(id.name, task,
		startTime + id.device->syncOffset(), id.callingSite);
	//printf("registering context event with kernel = %d.\n", id.name);
	if (id.contextEventMap != NULL)
	{
		for (TauGpuContextMap::iterator it = id.contextEventMap->begin();
				 it != id.contextEventMap->end();
				 it++)
		{
			TauContextUserEvent* e = it->first;
			TAU_EVENT_DATATYPE event_data = it->second;
			TAU_CONTEXT_EVENT_THREAD(e, event_data, task);
		}
	}
	break_gpu_event(id.name, task,
			endTime + id.device->syncOffset(), id.callingSite);
	
}

void Tau_gpu_register_memcpy_event(eventId id, double startTime, double endTime, int transferSize, int memcpyType)
{
	int task = get_task(id.device);
	//printf("in Tau_gpu.\n");
	//printf("Memcpy type is %d.\n", memcpyType);
	const char* functionName = id.name;
	if (strcmp(functionName, TAU_GPU_USE_DEFAULT_NAME) == 0)
	{
		if (memcpyType == MemcpyHtoD) {
			functionName = "Memory copy Host to Device";
		}
		else if (memcpyType == MemcpyDtoH)
		{
			functionName = "Memory copy Device to Host";
		}
		else 
		{
			functionName = "Memory copy (Other)";
		}
		//printf("using default name: %s.\n", functionName);
	}

#ifdef DEBUG_PROF		
	printf("recording memcopy event.\n");
	printf("time is: %f:%f.\n", startTime, endTime);
#endif
	if (memcpyType == MemcpyHtoD) {
		stage_gpu_event(functionName, task,
				startTime + id.device->syncOffset(), id.callingSite);
		//TAU_REGISTER_EVENT(MemoryCopyEventHtoD, "Memory copied from Host to Device");
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			counted_memcpys++;
			TAU_CONTEXT_EVENT(MemoryCopyEventHtoD, transferSize);
			//TAU_EVENT(MemoryCopyEventHtoD(), transferSize);
		//TauTraceEventSimple(TAU_ONESIDED_MESSAGE_RECV, transferSize, RtsLayer::myThread()); 
#ifdef DEBUG_PROF		
		printf("[%f] onesided event mem recv: %f, id: %s.\n", startTime, transferSize,
		id.device->printId());
#endif
		}
		break_gpu_event(functionName, task,
				endTime + id.device->syncOffset(), id.callingSite);
		//Inorder to capture the entire memcpy transaction time start the send/recived
		//at the start of the event
		TauTraceOneSidedMsg(MESSAGE_RECV, id.device, transferSize, task);
	}
	else if (memcpyType == MemcpyDtoH) {
		stage_gpu_event(functionName, task,
				startTime + id.device->syncOffset(), id.callingSite);
		//TAU_REGISTER_EVENT(MemoryCopyEventDtoH, "Memory copied from Device to Host");
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			counted_memcpys++;
			TAU_CONTEXT_EVENT(MemoryCopyEventDtoH, transferSize);
			//TAU_EVENT(MemoryCopyEventDtoH(), transferSize);
#ifdef DEBUG_PROF		
		printf("[%f] onesided event mem send: %f, id: %s\n", startTime, transferSize,
		id.device->printId());
#endif
		}
		//printf("TAU: putting message into trace file.\n");
		//printf("[%f] onesided event mem send: %f.\n", startTime, transferSize);
		break_gpu_event(functionName, task,
				endTime + id.device->syncOffset(), id.callingSite);
		//Inorder to capture the entire memcpy transaction time start the send/recived
		//at the start of the event
		TauTraceOneSidedMsg(MESSAGE_SEND, id.device, transferSize, task);
	}
	else {
		stage_gpu_event(functionName, task,
				startTime + id.device->syncOffset(), id.callingSite);
		//TAU_REGISTER_EVENT(MemoryCopyEventDtoH, "Memory copied from Device to Host");
		if (transferSize != TAU_GPU_UNKNOW_TRANSFER_SIZE)
		{
			counted_memcpys++;
			TAU_CONTEXT_EVENT(MemoryCopyEventDtoD, transferSize);
			//TAU_EVENT(MemoryCopyEventDtoH(), transferSize);
#ifdef DEBUG_PROF		
		printf("[%f] onesided event mem send: %f, id: %s\n", startTime, transferSize,
		id.device->printId());
#endif
		}
		//TauTraceEventSimple(TAU_ONESIDED_MESSAGE_RECV, transferSize, RtsLayer::myThread()); 
		//TauTraceOneSidedMsg(MESSAGE_SEND, device, transferSize, gpuTask);
		break_gpu_event(functionName, task,
				endTime + id.device->syncOffset(), id.callingSite);
	}

}
/*
	Initialization routine for TAU
*/
void Tau_gpu_init(void)
{
		//TAU_PROFILE_SET_NODE(0);
		//TAU_PROFILER_CREATE(main_ptr, ".TAU application", "", TAU_USER);
		//TAU_PROFILER_CREATE(main_ptr, "main", "", TAU_USER);

		//init context event.
		Tau_get_context_userevent((void **) &MemoryCopyEventHtoD, "Bytes copied from Host to Device");
		Tau_get_context_userevent((void **) &MemoryCopyEventDtoH, "Bytes copied from Device to Host");
		Tau_get_context_userevent((void **) &MemoryCopyEventDtoD, "Bytes copied (Other)");

		TAU_PROFILER_CREATE(gpu_ptr, ".TAU application  ", "", TAU_USER);

		/* Create a seperate GPU task */
		/*TAU_CREATE_TASK(gpuTask);


#ifdef DEBUG_PROF
		printf("Created user clock.\n");
#endif
		*/
		//TAU_PROFILER_START(main_ptr);	

		
#ifdef DEBUG_PROF
		printf("started main.\n");
#endif

}


/*
	finalization routine for TAU
*/
void Tau_gpu_exit(void)
{
		if (counted_memcpys != 0)
		{
			cerr << "TAU: warning not all bytes tranfered between CPU and GPU were recorded, some data maybe be incorrect." << endl;
		}
#ifdef DEBUG_PROF
		cerr << "stopping first gpu event.\n" << endl;
		printf("stopping level %d tasks.\n", number_of_tasks);
#endif
		map<gpuId*, int>::iterator it;
		for (it = TheGpuIdMap().begin(); it != TheGpuIdMap().end(); it++)
		{
			TAU_PROFILER_STOP_TASK(gpu_ptr, it->second);
		}
#ifdef DEBUG_PROF
		printf("stopping level 1.\n");
#endif
		//TAU_PROFILER_STOP(main_ptr);
#ifdef DEBUG_PROF
		printf("stopping level 2.\n");
#endif
	  //TAU_PROFILE_EXIT("tau_gpu");
    //Tau_stop_top_level_timer_if_necessary();
}
