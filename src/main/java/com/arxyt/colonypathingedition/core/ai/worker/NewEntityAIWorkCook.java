package com.arxyt.colonypathingedition.core.ai.worker;

import com.arxyt.colonypathingedition.api.workersetting.BuildingCookExtra;
import com.arxyt.colonypathingedition.core.ai.minimal.NewEntityAIEat;
import com.arxyt.colonypathingedition.core.util.NewFoodUtils;
import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.requestsystem.requestable.Food;
import com.minecolonies.api.colony.requestsystem.requestable.IRequestable;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.*;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook;
import com.minecolonies.core.colony.interactionhandling.StandardInteraction;
import com.minecolonies.core.colony.jobs.JobCook;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Predicate;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.CitizenConstants.FULL_SATURATION;
import static com.minecolonies.api.util.constant.Constants.*;
import static com.minecolonies.api.util.constant.StatisticsConstants.FOOD_SERVED;
import static com.minecolonies.api.util.constant.StatisticsConstants.FOOD_SERVED_DETAIL;
import static com.minecolonies.api.util.constant.TranslationConstants.*;
import static com.minecolonies.core.colony.buildings.modules.BuildingModules.RESTAURANT_MENU;

public class NewEntityAIWorkCook extends AbstractEntityAIUsesFurnace<JobCook, BuildingCook>
{
    /**
     * The amount of food which should be served to the worker.
     */
    public static final int SATURATION_TO_SERVE = 16;

    /**
     * Delay between each serving.
     */
    private static final int SERVE_DELAY = 10;

    /**
     * The citizen the worker is currently trying to serve.
     */
    private final Queue<AbstractEntityCitizen> citizenToServe = new ArrayDeque<>();

    /**
     * The citizen the worker is currently trying to serve.
     */
    private final Queue<Player> playerToServe = new ArrayDeque<>();

    /**
     * Cooking icon
     */
    private final static VisibleCitizenStatus COOK =
            new VisibleCitizenStatus(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/icons/work/cook.png"), "com.minecolonies.gui.visiblestatus.cook");

    /**
     * Initial all the citizen may should be served
     */
    private Queue<Integer> initailCitizenToServe = new ArrayDeque<>();

    /**
     * Xp give per action
     */
    private static final double BASE_XP_GAIN = 2;

    /**
     * If cook check customer
     */
    boolean checkCustomer = true;

    /**
     * A directly return to serve citizens after pick up
     */
    AIWorkerState withSpecialReturn = START_WORKING;

    /**
     * Constructor for the Cook. Defines the tasks the cook executes.
     *
     * @param job a cook job to use.
     */
    public NewEntityAIWorkCook(@NotNull final JobCook job)
    {
        super(job);
        super.registerTargets(
                new AITarget(COOK_SERVE_FOOD_TO_CITIZEN, this::serveFoodToCitizen, SERVE_DELAY),
                new AITarget(COOK_SERVE_FOOD_TO_PLAYER, this::serveFoodToPlayer, SERVE_DELAY)
        );
        worker.setCanPickUpLoot(true);
    }

    @Override
    public IAIState getStateAfterPickUp()
    {
        AIWorkerState returnState = withSpecialReturn;
        withSpecialReturn = START_WORKING;
        return returnState;
    }

    @Override
    public Class<BuildingCook> getExpectedBuildingClass()
    {
        return BuildingCook.class;
    }

    /**
     * Very simple action, cook straightly extract it from the furnace.
     * Note: retrieveSmeltableFromFurnace in AbstractEntityAIUsesFurnace already calls incrementActionsDoneAndDecSaturation().
     *
     * @param furnace the furnace to retrieve from.
     */
    @Override
    protected void extractFromFurnace(final FurnaceBlockEntity furnace)
    {
        InventoryUtils.transferItemStackIntoNextFreeSlotInItemHandler(
                new InvWrapper(furnace), RESULT_SLOT,
                worker.getInventoryCitizen());
        worker.getCitizenExperienceHandler().addExperience(BASE_XP_GAIN);
    }

    @Override
    public IAIState startWorking()
    {
        if (building.isInBuilding(worker.blockPosition()))
        {
            final IAIState importantState = checkForImportantJobs();
            if (importantState != START_WORKING)
            {
                return importantState;
            }
        }
        return super.startWorking();
    }

    @Override
    protected boolean isSmeltable(final ItemStack stack)
    {
        //Only return true if the item isn't queued for a recipe.
        return ItemStackUtils.ISCOOKABLE.test(stack) && building.getModule(RESTAURANT_MENU).getMenu().contains(new ItemStorage(MinecoloniesAPIProxy.getInstance().getFurnaceRecipes().getSmeltingResult(stack)));
    }

    @Override
    protected boolean reachedMaxToKeep()
    {
        if (super.reachedMaxToKeep())
        {
            return true;
        }
        final int buildingLimit = Math.max(1, building.getBuildingLevel() * building.getBuildingLevel()) * SLOT_PER_LINE;
        return InventoryUtils.getCountFromBuildingWithLimit(building,
                NewFoodUtils.EDIBLE.and(stack -> NewFoodUtils.canEatLevel(stack, building.getBuildingLevel() - 1)),
                stack -> stack.getMaxStackSize() * 6) > buildingLimit;
    }

    @Override
    public void requestSmeltable()
    {
        final RestaurantMenuModule menuModule = building.getModule(RESTAURANT_MENU);
        if (menuModule.getMenu().isEmpty() && worker.getCitizenData() != null)
        {
            worker.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatable(FURNACE_USER_NO_FOOD), ChatPriority.BLOCKING));
        }
    }

    private int canServeInRow()
    {
        return Math.max(4, getPrimarySkillLevel() / 10 + 3);
    }

    /**
     * Checks whether this cook is currently actively serving or gathering food for the given customer.
     */
    public boolean isServingCustomer(final int citizenId)
    {
        if (worker instanceof EntityCitizen citizen && citizen.getCitizenAI().getState() != com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.WORK)
        {
            return false;
        }
        final IAIState currentState = getState();
        if (currentState != COOK_SERVE_FOOD_TO_CITIZEN
                && !(currentState == GATHERING_REQUIRED_MATERIALS && withSpecialReturn == COOK_SERVE_FOOD_TO_CITIZEN))
        {
            return false;
        }
        if (initailCitizenToServe.contains(citizenId))
        {
            return true;
        }
        for (final AbstractEntityCitizen citizen : citizenToServe)
        {
            if (citizen != null && citizen.getCivilianID() == citizenId)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases any unserved customers back to the building queue if the cook is interrupted.
     */
    public void releaseUnservedCustomers()
    {
        final BuildingCookExtra buildingExtra = (BuildingCookExtra) building;
        while (!initailCitizenToServe.isEmpty())
        {
            final Integer id = initailCitizenToServe.poll();
            if (id != null)
            {
                buildingExtra.releaseProcessingCustomer(id, true);
            }
        }
        while (!citizenToServe.isEmpty())
        {
            final AbstractEntityCitizen citizen = citizenToServe.poll();
            if (citizen != null)
            {
                buildingExtra.releaseProcessingCustomer(citizen.getCivilianID(), true);
            }
        }
        checkCustomer = true;
    }

    /**
     * Sorts citizenToServe so the customer closest to the worker's current position is served next.
     */
    private void sortCitizensToServeByDistance()
    {
        if (citizenToServe.size() <= 1)
        {
            return;
        }
        final java.util.List<AbstractEntityCitizen> sorted = new java.util.ArrayList<>(citizenToServe);
        final net.minecraft.core.BlockPos workerPos = worker.blockPosition();
        sorted.sort(java.util.Comparator.comparingDouble(c -> c.blockPosition().distSqr(workerPos)));
        citizenToServe.clear();
        citizenToServe.addAll(sorted);
    }

    /**
     * Serve food to citizen.
     * @return next IAIState
     */
    private IAIState serveFoodToCitizen()
    {
        worker.getCitizenData().setVisibleStatus(COOK);
        final BuildingCookExtra buildingExtra = (BuildingCookExtra) building;
        final RestaurantMenuModule module = building.getModule(RESTAURANT_MENU);

        if (checkCustomer)
        {
            while (!initailCitizenToServe.isEmpty())
            {
                final int citizenID = initailCitizenToServe.poll();
                final ICitizenData citizenData = building.getColony().getCitizenManager().getCivilian(citizenID);
                if (citizenData == null || citizenData.getEntity().isEmpty())
                {
                    buildingExtra.deleteCustomer(citizenID);
                    continue;
                }
                final AbstractEntityCitizen citizen = citizenData.getEntity().get();
                if (!building.isInBuilding(citizen.blockPosition()) || citizenData.getSaturation() >= CitizenConstants.FULL_SATURATION)
                {
                    buildingExtra.deleteCustomer(citizenID);
                    continue;
                }

                if (NewFoodUtils.hasBestOptionInInv(worker.getInventoryCitizen(), citizenData, module.getMenu(), building))
                {
                    citizenToServe.add(citizen);
                }
                else
                {
                    final ItemStorage storage = NewFoodUtils.checkForFoodInBuilding(citizenData, module.getMenu(), building);
                    if (storage != null)
                    {
                        citizenToServe.add(citizen);
                        needsCurrently = new Tuple<>(stack -> new ItemStorage(stack).equals(storage), STACKSIZE);
                        withSpecialReturn = COOK_SERVE_FOOD_TO_CITIZEN;
                        return GATHERING_REQUIRED_MATERIALS;
                    }
                    else if (NewFoodUtils.getBestFoodForCitizenWithRestaurantCheck(worker.getInventoryCitizen(), citizenData, module.getMenu(), false) != -1)
                    {
                        citizenToServe.add(citizen);
                    }
                    else
                    {
                        buildingExtra.deleteCustomer(citizenID);
                    }
                }
            }
            checkCustomer = false;
            sortCitizensToServeByDistance();
        }

        // Remove invalid, absent, or overtime customers from head of queue.
        while (!citizenToServe.isEmpty())
        {
            final AbstractEntityCitizen next = citizenToServe.peek();
            if (next == null || !next.isAlive() || !building.isInBuilding(next.blockPosition())
                    || next.getCitizenData() == null || next.getCitizenData().getSaturation() >= CitizenConstants.FULL_SATURATION)
            {
                if (next != null)
                {
                    buildingExtra.deleteCustomer(next.getCivilianID());
                }
                citizenToServe.poll();
                continue;
            }
            if (!buildingExtra.checkCustomerRegistry(next.getCivilianID()))
            {
                citizenToServe.poll();
                continue;
            }
            break;
        }

        if (citizenToServe.isEmpty())
        {
            initailCitizenToServe.clear();
            checkCustomer = true;
            final int customerSize = buildingExtra.checkSize();
            if (customerSize > 0)
            {
                final int shouldServeInRow = getShouldServeInRow(customerSize, canServeInRow());
                initailCitizenToServe.addAll(buildingExtra.getCustomers(shouldServeInRow));
                if (!initailCitizenToServe.isEmpty())
                {
                    return COOK_SERVE_FOOD_TO_CITIZEN;
                }
            }
            worker.getNavigation().stop();
            return START_WORKING;
        }

        final AbstractEntityCitizen targetCitizen = citizenToServe.peek();
        final ICitizenData targetData = targetCitizen.getCitizenData();

        // Before walking to the customer, make sure we still have sufficient food in our inventory; if not, restock from racks.
        final int preCheckSlot = NewFoodUtils.getBestFoodForCitizenWithRestaurantCheck(worker.getInventoryCitizen(), targetData, module.getMenu(), false);
        if (preCheckSlot == -1)
        {
            final ItemStorage storage = NewFoodUtils.checkForFoodInBuilding(targetData, module.getMenu(), building);
            if (storage != null)
            {
                needsCurrently = new Tuple<>(stack -> new ItemStorage(stack).equals(storage), STACKSIZE);
                withSpecialReturn = COOK_SERVE_FOOD_TO_CITIZEN;
                return GATHERING_REQUIRED_MATERIALS;
            }
            else
            {
                buildingExtra.deleteCustomer(targetCitizen.getCivilianID());
                citizenToServe.poll();
                return COOK_SERVE_FOOD_TO_CITIZEN;
            }
        }
        else
        {
            final ItemStack stackInSlot = worker.getInventoryCitizen().getStackInSlot(preCheckSlot);
            final double foodVal = Math.max(0.1, NewFoodUtils.getFoodValue(stackInSlot, targetCitizen));
            final int neededQty = Math.max(1, (int) Math.ceil((FULL_SATURATION - targetData.getSaturation()) / foodVal));
            final int availableInInv = InventoryUtils.getItemCountInItemHandler(
                    worker.getInventoryCitizen(),
                    s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stackInSlot));
            if (availableInInv < neededQty)
            {
                final ItemStorage neededStorage = new ItemStorage(stackInSlot);
                if (InventoryUtils.getCountFromBuilding(building, List.of(neededStorage)) > 0)
                {
                    needsCurrently = new Tuple<>(stack -> new ItemStorage(stack).equals(neededStorage), STACKSIZE);
                    withSpecialReturn = COOK_SERVE_FOOD_TO_CITIZEN;
                    return GATHERING_REQUIRED_MATERIALS;
                }
            }
        }

        if (!walkToWorkPos(targetCitizen.blockPosition()))
        {
            return getState();
        }

        final AbstractEntityCitizen citizen = citizenToServe.poll();
        sortCitizensToServeByDistance();
        assert citizen != null;
        final InventoryCitizen handler = citizen.getInventoryCitizen();
        final ICitizenData citizenData = citizen.getCitizenData();

        if (citizenData.getSaturation() >= FULL_SATURATION)
        {
            buildingExtra.deleteCustomer(citizen.getCivilianID());
            return getState();
        }

        int totalServedCount = 0;
        double remainingSatNeeded = FULL_SATURATION - citizenData.getSaturation();

        while (remainingSatNeeded > 0)
        {
            final int foodSlot = NewFoodUtils.getBestFoodForCitizenWithRestaurantCheck(worker.getInventoryCitizen(), citizenData, module.getMenu(), false);
            if (foodSlot == -1)
            {
                break;
            }

            final ItemStack slotStack = worker.getInventoryCitizen().getStackInSlot(foodSlot);
            final double foodValue = Math.max(0.1, NewFoodUtils.getFoodValue(slotStack, citizen));
            final int qtyNeeded = Math.max(1, (int) Math.ceil(remainingSatNeeded / foodValue));
            final int qtyToTake = Math.min(qtyNeeded, slotStack.getCount());
            final String foodName = slotStack.getDescriptionId();

            final ItemStack extracted = worker.getInventoryCitizen().extractItem(foodSlot, qtyToTake, false);
            if (extracted.isEmpty())
            {
                break;
            }

            final ItemStack remainder = InventoryUtils.addItemStackToItemHandlerWithResult(handler, extracted);
            final int insertedCount = extracted.getCount() - remainder.getCount();
            int fedDirectlyCount = 0;

            if (!remainder.isEmpty())
            {
                while (!remainder.isEmpty() && citizenData.getSaturation() < FULL_SATURATION)
                {
                    final ItemStack singleFood = remainder.split(1);
                    citizenData.getCitizenFoodHandler().addLastEaten(singleFood.getItem());
                    NewFoodUtils.consumeFood(singleFood, citizen, null);
                    fedDirectlyCount++;
                }
                if (!remainder.isEmpty())
                {
                    InventoryUtils.addItemStackToItemHandler(worker.getInventoryCitizen(), remainder);
                }
            }

            final int servedNow = insertedCount + fedDirectlyCount;
            if (servedNow <= 0)
            {
                break;
            }

            totalServedCount += servedNow;
            remainingSatNeeded -= servedNow * foodValue;
            Objects.requireNonNull(worker.getCitizenColonyHandler().getColonyOrRegister())
                    .getStatisticsManager().incrementBy(FOOD_SERVED, servedNow, worker.getCitizenColonyHandler().getColonyOrRegister().getDay());
            StatsUtil.trackStatByName(building, FOOD_SERVED_DETAIL, foodName, servedNow);

            if (citizenData.getSaturation() >= FULL_SATURATION)
            {
                break;
            }
        }

        if (totalServedCount > 0)
        {
            if (citizenData.getHomeBuilding() != null && citizenData.getHomeBuilding().getBuildingLevel() > building.getBuildingLevel() + 1)
            {
                worker.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatable(POOR_RESTAURANT_INTERACTION), ChatPriority.BLOCKING));
            }
            buildingExtra.deleteCustomer(citizen.getCivilianID());
            worker.getCitizenExperienceHandler().addExperience(BASE_XP_GAIN);
            worker.decreaseSaturationForAction();
        }
        else
        {
            final ItemStorage storage = NewFoodUtils.checkForFoodInBuilding(citizenData, module.getMenu(), building);
            if (storage != null)
            {
                citizenToServe.add(citizen);
                needsCurrently = new Tuple<>(stack -> new ItemStorage(stack).equals(storage), STACKSIZE);
                withSpecialReturn = COOK_SERVE_FOOD_TO_CITIZEN;
                return GATHERING_REQUIRED_MATERIALS;
            }
            buildingExtra.deleteCustomer(citizen.getCivilianID());
        }

        return getState();
    }

    /**
     * Serve food to player.
     * @return next IAIState
     */
    private IAIState serveFoodToPlayer()
    {
        if (playerToServe.isEmpty())
        {
            return START_WORKING;
        }

        BuildingCookExtra extra = (BuildingCookExtra) building;
        worker.getCitizenData().setVisibleStatus(COOK);
        if (playerToServe.peek() == null || !building.isInBuilding(playerToServe.peek().blockPosition()) || playerToServe.peek().getFoodData().getFoodLevel() >= 18)
        {
            worker.getNavigation().stop();
            Player player = playerToServe.poll();
            if(player != null) {
                extra.removePlayerServing(player.getUUID());
            }
            return COOK_SERVE_FOOD_TO_PLAYER;
        }

        assert playerToServe.peek() != null;
        if (!walkToWorkPos(playerToServe.peek().blockPosition()))
        {
            return getState();
        }

        final Player player = playerToServe.poll();
        assert player != null;
        final IItemHandler handler = new InvWrapper(player.getInventory());
        final RestaurantMenuModule module = worker.getCitizenData().getWorkBuilding().getModule(RESTAURANT_MENU);
        final Predicate<ItemStack> canEatPredicate = stack -> module.getMenu().contains(new ItemStorage(stack));
        if (InventoryUtils.isItemHandlerFull(handler))
        {
            worker.getNavigation().stop();
            extra.removePlayerServing(player.getUUID());
            return COOK_SERVE_FOOD_TO_PLAYER;
        }

        final Object2IntMap<ItemStack> transferredItemMap = InventoryUtils.transferFoodUpToSaturation(worker, handler, building.getBuildingLevel() * SATURATION_TO_SERVE, canEatPredicate);
        int count = 0;
        for (int v : transferredItemMap.values()) count += v;

        if (count <= 0)
        {
            extra.removePlayerListServing(playerToServe);
            playerToServe.clear();
            return START_WORKING;
        }

        extra.setPlayerServed(player.getUUID());
        worker.getCitizenColonyHandler().getColonyOrRegister().getStatisticsManager().incrementBy(FOOD_SERVED, count, worker.getCitizenColonyHandler().getColonyOrRegister().getDay());
        StatsUtil.trackStatByStackMap(building, FOOD_SERVED_DETAIL, transferredItemMap);
        MessageUtils.format(MESSAGE_INFO_CITIZEN_COOK_SERVE_PLAYER, worker.getName().getString()).sendTo(player);

        worker.getCitizenExperienceHandler().addExperience(BASE_XP_GAIN);
        this.worker.decreaseSaturationForContinuousAction();

        return playerToServe.isEmpty() ? START_WORKING : COOK_SERVE_FOOD_TO_PLAYER;
    }

    /**
     * Checks if the cook has anything important to do before going to the default furnace user jobs. First calculate the building range if not cached yet. Then check for citizens
     * around the building. If no citizen around switch to default jobs. If citizens around check if food in inventory, if not, switch to gather job. If food in inventory switch to
     * serve job.
     *
     * @return the next IAIState to transfer to.
     */
    @Override
    protected IAIState checkForImportantJobs()
    {
        final List<? extends Player> playerList = WorldUtil.getEntitiesWithinBuilding(world, Player.class,
                building, player -> player != null
                        && player.getFoodData().getFoodLevel() < 18
                        && building.getColony().getPermissions().hasPermission(player, Action.MANAGE_HUTS)
                        && ((BuildingCookExtra)building).getPlayCanServe(player.getUUID())
        );

        playerToServe.addAll(playerList);

        //修改警告条件至餐厅菜单中菜品数量
        final RestaurantMenuModule module = building.getModule(RESTAURANT_MENU);
        int menuDiversity = 0;
        for (ItemStorage menuItem : module.getMenu())
        {
            if(NewFoodUtils.canEatLevel(menuItem.getItemStack(),building.getBuildingLevel())){
                menuDiversity ++;
            }
            if(menuDiversity >= NewFoodUtils.getMinFoodDiversityRequirement(building)){
                break;
            }
        }
        if (menuDiversity < NewFoodUtils.getMinFoodDiversityRequirement(building))
        {
            worker.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatable(POOR_MENU_INTERACTION), ChatPriority.BLOCKING));
        }

        final BuildingCookExtra cookExtra = (BuildingCookExtra) building;
        //检查在路上的客人是否已经取消预订或已到店
        for(int customerId : cookExtra.getOrders()) {
            ICitizenData citizenData = building.getColony().getCitizenManager().getCivilian(customerId);
            if (citizenData == null || citizenData.getEntity().isEmpty()) {
                cookExtra.reached(customerId);
            } else if (citizenData.getEntity().get() instanceof EntityCitizen citizen) {
                IState state = citizen.getCitizenAI().getState();
                if (state != NewEntityAIEat.NewEatingState.CHECK_FOOD && state != NewEntityAIEat.NewEatingState.GO_TO_RESTAURANT) {
                    cookExtra.reached(customerId);
                }
            }
        }

        if (!playerToServe.isEmpty())
        {
            ((BuildingCookExtra)building).setPlayerServing(playerToServe);
            final Predicate<ItemStack> foodPredicate = stack -> module.getMenu().contains(new ItemStorage(stack));
            if (!InventoryUtils.hasItemInItemHandler(worker.getInventoryCitizen(), foodPredicate))
            {
                if (InventoryUtils.hasItemInProvider(building, foodPredicate))
                {
                    needsCurrently = new Tuple<>(foodPredicate, STACKSIZE);
                    withSpecialReturn = COOK_SERVE_FOOD_TO_PLAYER;
                    return GATHERING_REQUIRED_MATERIALS;
                }
            }
            return COOK_SERVE_FOOD_TO_PLAYER;
        }


        final int customerSize = cookExtra.checkSize();
        if (initailCitizenToServe.isEmpty() && citizenToServe.isEmpty() && customerSize > 0) {
            final int canServeInRow = canServeInRow();
            int shouldServeInRow = getShouldServeInRow(customerSize, canServeInRow);
            initailCitizenToServe = new ArrayDeque<>(cookExtra.getCustomers(shouldServeInRow));
            checkCustomer = true;
        }

        if (!initailCitizenToServe.isEmpty() || !citizenToServe.isEmpty())
        {
            return COOK_SERVE_FOOD_TO_CITIZEN;
        }

        return START_WORKING;
    }

    private int getShouldServeInRow(int customerSize, int canServeInRow) {
        final int workerSize = Math.max(1, building.getAllAssignedCitizen().size());
        int shouldServeInRow = (customerSize - 1) / workerSize + 1;
        // The check customerSize <= 4 is to prevent the allocation efficiency from being affected when a cook fails to start work for some reason; this approach is relatively less costly compared to detecting which cook is on duty.
        if (customerSize <= 4){
            shouldServeInRow = Math.min(canServeInRow, customerSize);
        }
        else if (canServeInRow < shouldServeInRow) {
            shouldServeInRow = canServeInRow;
        }
        return shouldServeInRow;
    }

    @Override
    protected int getActionsDoneUntilDumping()
    {
        if (!citizenToServe.isEmpty() || !initailCitizenToServe.isEmpty() || !playerToServe.isEmpty())
        {
            return Integer.MAX_VALUE;
        }
        if (((BuildingCookExtra) building).checkSize() > 0)
        {
            return 32;
        }
        return getPositionOfOvenToRetrieveFrom() == null ? 1 : Math.max(1, building.getBuildingLevel());
    }

    @Override
    protected IRequestable getSmeltAbleClass()
    {
        return new Food(STACKSIZE, building.getBuildingLevel());
    }
}
