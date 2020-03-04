

//simple add
EXIT_EXC    EQU 64          // n° d'exception de EXIT
READ_EXC    EQU 65          // n° d'exception de READ (lit 1 ligne)
WRITE_EXC   EQU 66          // n° d'exception de WRITE (affiche 1 ligne)
STACK_ADRS  EQU 0x1000      // base de pile en 1000h (par exemple)
LOAD_ADRS   EQU 0xF000      // adresse de chargement de l'exécutable

// ces alias permettront de changer les réels registres utilisés
SP          EQU R15         // alias pour R15, pointeur de pile
WR          EQU R14         // Work Register (registre de travail)
BP          EQU R13         // frame Base Pointer (pointage environnement)
                            // R12, R11 réservés
                            // R0 pour résultat de fonction
                            // R1 ... R10 disponibles


ORG         LOAD_ADRS       // adresse de chargement
START       main_           // adresse de démarrage

PARAM1      EQU 1
PARAM2      EQU 1


main_   
    LDW R1, #PARAM1         // charge adresse de PARAM1 dans R1
    STW R1, -(SP)           // empile paramètre p = STRING0 contenu dans R1 
    LDW R2, #PARAM2         // charge adresse de PARAM2 dans R2
    STW R2, -(SP)           // empile paramètre p = PARAM2 contenu dans R2
    ADD R1, R2, R0          // somme contenu de R1 et R2 dans R0
    ADQ -2, SP              // décrémente le pointeur de pile SP
    ADQ -2, SP              // décrémente le pointeur de pile SP
    TRP #EXIT_EXC           // EXIT: arrête le programme


