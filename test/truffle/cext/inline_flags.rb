# Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 1.0
# GNU General Public License version 2
# GNU Lesser General Public License version 2.1

p Truffle::CExt.supported?

Truffle::CExt.inline %{
  #include <stdio.h>
}, %{
  printf("FOO was defined to be %d\\n", FOO);
}, %w(-DFOO=14)
