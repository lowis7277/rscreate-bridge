# RS Create Bridge demo.
# Usage: standing in a flat creative world, /function rscbtest:demo (builds everything 2 blocks to the south-east).
rscbtest demo
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
time set day
weather clear
give @p create:wrench
give @p create:goggles
give @p refinedstorage:pattern 8
tellraw @p [{"text":"[RS Create Bridge] ","color":"gold"},{"text":"Demo ready. Request from the Grid (RS block against the north wall): crushing wheels, precision mechanisms, sturdy sheets, tracks, brass or an extendo grip.","color":"yellow"}]
