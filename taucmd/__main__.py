"""
@file
@author John C. Linford (jlinford@paratools.com)
@version 1.0

@brief

This file is part of the TAU Performance System

@section COPYRIGHT

Copyright (c) 2013, ParaTools, Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without 
modification, are permitted provided that the following conditions are met:
 (1) Redistributions of source code must retain the above copyright notice, 
     this list of conditions and the following disclaimer.
 (2) Redistributions in binary form must reproduce the above copyright notice, 
     this list of conditions and the following disclaimer in the documentation 
     and/or other materials provided with the distribution.
 (3) Neither the name of ParaTools, Inc. nor the names of its contributors may 
     be used to endorse or promote products derived from this software without 
     specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
"""

import os
import sys
import re
import types
import logging
import traceback
import pkgutil
import signal
import textwrap
import taucmd
from textwrap import dedent
from docopt import docopt
from taucmd import EXPECT_PYTHON_VERSION, TAU_ROOT_DIR 
from taucmd import TauConfigurationError, TauNotImplementedError
from taucmd import commands
from taucmd import compiler

USAGE = """
================================================================================
The Tau Performance System (version %(tau_version)s)
http://tau.uoregon.edu/

Usage:
  tau [--help] [--version] [--log=<level>] [--home=<path>] [--config=<name>] <command> [<args>...]

Options:
  --home=<path>      Tau configuration home. [default: %(home_default)s]
  --config=<name>    Tau configuration. [default: %(config_default)s]
  --log=<level>      Output level.  [default: %(log_default)s]
                     <level> can be CRITICAL, ERRROR, WARNING, INFO, or DEBUG
  --help             Show usage.
  --version          Show version.
  
  <command> may be a compiler (e.g. gcc, mpif90) or a subcommand
  
Known Compilers:
%(compilers)s 

Subcommands:
%(commands)s
================================================================================
"""

LOGGER = taucmd.getLogger(__name__)

def lookup_tau_version():
    """
    Opens TAU.h to get the TAU version
    """
    if not TAU_ROOT_DIR:
        return '(unknown)'
    with open('%s/include/TAU.h' % TAU_ROOT_DIR, 'r') as tau_h:
        pattern = re.compile('#define\s+TAU_VERSION\s+"(.*)"')
        for line in tau_h:
            match = pattern.match(line) 
            if match:
                return match.group(1)
    return '(unknown)'


def get_known_compilers():
    known = ', '.join(compiler.known_compiler_commands())
    return textwrap.fill(known, width=70, initial_indent='  ', subsequent_indent='  ')

def get_command_list():
    """
    Builds listing of command names with short description
    """
    parts = []
    for module in [name for _, name, _ in pkgutil.walk_packages(path=commands.__path__, prefix=commands.__name__+'.')]:
        __import__(module)
        descr = sys.modules[module].SHORT_DESCRIPTION
#        _temp = __import__(module, globals(), locals(), ['SHORT_DESCRIPTION'], -1)
#        descr = _temp.SHORT_DESCRIPTION
        name = '{:<12}'.format(module.split('.')[-1])
        parts.append('  %s  %s' % (name, descr))
    return '\n'.join(parts)



def taucmd_excepthook(etype, e, tb):
    """
    Exception handler for any uncaught exception (except SystemExit).
    """
    if etype == TauConfigurationError:
        hint = 'Hint: %s\n' % e.hint if e.hint else ''
        message = dedent("""
        %(value)s
        %(hint)s
        Tau cannot proceed with the given inputs.
        Please review the input files and command line parameters or contact %(contact)s for assistance.
        """ % {'value': e.value, 'hint': hint, 'contact': taucmd.HELP_CONTACT})
        LOGGER.critical(message)
        sys.exit(-1)
    elif etype == TauNotImplementedError:
        hint = 'Hint: %s\n' % e.hint if e.hint else ''
        message = dedint("""
        Unimplemented feature %(missing)r: %(value)s
        %(hint)s
        Sorry, you have requested a feature that is not yet implemented.
        Please contact %(contact)s for assistance.
        """ % {'missing': e.missing, 'value': e.value, 'hint': hint, 'contact': taucmd.HELP_CONTACT})
        LOGGER.critical(message)
        sys.exit(-1)
    else:
        traceback.print_exception(etype, e, tb)
        args = [arg for arg in sys.argv[1:] if not '--log' in arg] 
        message = dedent("""
        An unexpected %(typename)s exception was raised.
        Please contact <tau-bugs@cs.uoregon.edu> for assistance.
        If possible, please include the output of this command:

        tau --log=DEBUG %(cmd)s
        """ % {'typename': etype.__name__, 'cmd': ' '.join(args)})
        LOGGER.critical(message)
        sys.exit(-1)

        
def main():
    """
    Program entry point
    """

    # Set the default exception handler
    sys.excepthook = taucmd_excepthook

    # Check Python version
    if sys.version_info < EXPECT_PYTHON_VERSION:
        version = '.'.join(map(str, sys.version_info[0:3]))
        expected = '.'.join(map(str, EXPECT_PYTHON_VERSION))
        LOGGER.warning("Your Python version is %s, but 'tau' expects Python %s or later.  Please update Python." % (version, expected))

    # Get tau version
    tau_version = lookup_tau_version()
    
    # Parse command line arguments
    usage = USAGE % {'tau_version': tau_version,
                     'home_default': taucmd.HOME,
                     'config_default': taucmd.CONFIG,
                     'log_default': taucmd.LOG_LEVEL,
                     'compilers': get_known_compilers(),
                     'commands': get_command_list()}
    args = docopt(usage, version=tau_version, options_first=True)

    # Set log level
    taucmd.setLogLevel(args['--log'])
    LOGGER.debug('Arguments: %s' % args)
    LOGGER.debug('Verbosity level: %s' % taucmd.LOG_LEVEL)
    
    # Record global arguments
    taucmd.HOME = args['--home']
    taucmd.CONFIG = args['--config']
    
    # Try to execute as a tau command
    cmd = args['<command>']
    cmd_args = args['<args>']
    cmd_module = 'taucmd.commands.%s' % cmd
    try:
        __import__(cmd_module)
        LOGGER.debug('Recognized %r as tau subcommand' % cmd)
        return sys.modules[cmd_module].main([cmd] + cmd_args)
    except ImportError:
        # It wasn't a tau command, but that's OK
        LOGGER.debug('%r not recognized as tau subcommand' % cmd)

    # Try to execute as a compiler command
    try:
        retval = compiler.compile(args)
        if retval == 0:
            LOGGER.info('Compilation successful')
        elif retval > 0:
            LOGGER.critical('Compilation failed')
        elif proc.returncode < 0:
            signal_names = dict((getattr(signal, n), n) for n in dir(signal) 
                                if n.startswith('SIG') and '_' not in n)
            LOGGER.critical('Compilation aborted by signal %s' % signal_names[-proc.returncode])
        return retval
    except TauNotImplementedError:
        # It wasn't a compiler command, but that's OK
        LOGGER.debug('%r not recognized as a compiler command' % cmd)
        
    # Not sure what to do at this point, so advise the user and exit
    LOGGER.debug("Can't classify %r.  Calling 'tau help' to get advice." % cmd)
    return commands.help.main(['help', cmd])


# Command line execution
if __name__ == "__main__":
    exit(main())