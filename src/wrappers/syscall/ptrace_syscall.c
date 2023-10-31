#if defined(__powerpc64__) || defined(__PPC64__) || defined(__APPLE__)
#include "ptrace_syscall.old.c"
#else
// MIT License
//
// Copyright (c) 2022 Advanced Micro Devices, Inc. All Rights Reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#define _GNU_SOURCE

#include <TAU.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <tracee.h>

extern void Tau_profile_exit_all_threads(void);
extern int Tau_init_initializeTAU(void);

#if defined(__GNUC__)
#define __TAU_FUNCTION__ __PRETTY_FUNCTION__
#else
#define __TAU_FUNCTION__ __func__
#endif

// #define DEBUG_PTRACE

#ifdef DEBUG_PTRACE
#define DEBUG_PRINT(...)                                                                                               \
    fprintf(stderr, __VA_ARGS__);                                                                                      \
    fflush(stderr);
#else
#define DEBUG_PRINT(...)
#endif

pid_t rpid;

void __attribute__((constructor)) taupreload_init(void);

// Have to do this when using CUPTI, otherwise the initialization is made by
// Tau_cupti_onload() but we cannot use CUDA before a fork for the child to work
// properly
void taupreload_init()
{
    shared_num_tasks = (volatile int *)mmap(NULL, sizeof(int), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    waiting_for_ack = (volatile int *)mmap(NULL, sizeof(int), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    parent_has_dumped = (volatile int *)mmap(NULL, sizeof(int), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    task_creater_thread_tid = (volatile int *)mmap(NULL, sizeof(int), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);

    *shared_num_tasks = 0;
    *waiting_for_ack = 0;
    *parent_has_dumped = 0;
    *task_creater_thread_tid = -1;

    rpid = fork();
}

// Trampoline for the real main()
static int (*main_real)(int, char **, char **);

int taupreload_main(int argc, char **argv, char **envp)
{
    // prevent re-entry
    static int _reentry = 0;
    if (_reentry > 0)
        return -1;
    _reentry = 1;

    int ret;

    // does little, but does something
    TAU_INIT(&argc, &argv);
    // apparently is the real initialization.
    Tau_init_initializeTAU();
    int tmp = TAU_PROFILE_GET_NODE();
    if (tmp == -1)
    {
        TAU_PROFILE_SET_NODE(0);
    }

    if (rpid == 0)
    {
        pid_t ppid = getppid();
        /* Child */
        Tau_create_top_level_timer_if_necessary();
        void *handle;
        TAU_PROFILER_CREATE(handle, __TAU_FUNCTION__, "", TAU_DEFAULT);
        TAU_PROFILER_START(handle);

        prepare_to_be_tracked(ppid);

        ret = main_real(argc, argv, envp);

        // Tell parent to stop the tracking
        kill(ppid, SIG_STOP_PTRACE);
        DEBUG_PRINT("%d just sent signal SIG_STOP_PTRACE to %d\n", getpid(), ppid);

        // TODO replace with mutex
        while (!*parent_has_dumped)
        {
        }

        pthread_join(task_creater_thread, NULL);

        TAU_PROFILER_STOP(handle);
    }
    else
    {
        /* Parent */
        ret = track_process(rpid);

        munmap((int *) shared_num_tasks, sizeof(int));
        munmap((int *) waiting_for_ack, sizeof(int));
        munmap((int *) parent_has_dumped, sizeof(int));
        munmap((int *) task_creater_thread_tid, sizeof(int));
    }

    Tau_profile_exit_all_threads();
    Tau_destructor_trigger();

    return ret;
}

typedef int (*taupreload_libc_start_main)(int (*)(int, char **, char **), int, char **, int (*)(int, char **, char **),
                                          void (*)(void), void (*)(void), void *);

int __libc_start_main(int (*_main)(int, char **, char **), int _argc, char **_argv, int (*_init)(int, char **, char **),
                      void (*_fini)(void), void (*_rtld_fini)(void), void *_stack_end)
{
    // prevent re-entry
    static int _reentry = 0;
    if (_reentry > 0)
        return -1;
    _reentry = 1;

    // get the address of this function
    void *_this_func = __builtin_return_address(0);

    // Save the real main function address
    main_real = _main;

    // Find the real __libc_start_main()
    taupreload_libc_start_main user_main = (taupreload_libc_start_main)dlsym(RTLD_NEXT, "__libc_start_main");

    if (user_main && user_main != _this_func)
    {
        return user_main(taupreload_main, _argc, _argv, _init, _fini, _rtld_fini, _stack_end);
    }
    else
    {
        fputs("Error! taupreload could not find __libc_start_main!", stderr);
        return -1;
    }
}
#endif // __ppc64le__
