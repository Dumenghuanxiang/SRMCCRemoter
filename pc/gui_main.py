import sys

from srm_xbox.gui import main, smoke_test_main

if "--smoke-test" in sys.argv:
    smoke_test_main()
else:
    main()
