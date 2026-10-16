package com.arxyt.colonypathingedition.mixins.minecolonies.building;

import com.arxyt.colonypathingedition.api.workersetting.BuildingCookExtra;
import com.arxyt.colonypathingedition.core.ai.minimal.NewEntityAIEat;
import com.arxyt.colonypathingedition.core.ai.worker.NewEntityAIWorkCook;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mixin(value = BuildingCook.class, remap = false)
public abstract class BuildingCookMixin extends AbstractBuilding implements BuildingCookExtra {
    private final Queue<Integer> customerQueue = new ConcurrentLinkedQueue<>();
    private final Set<Integer> processingCustomers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> customersOnTheWay = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playerFed = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerServing = new ConcurrentHashMap<>();

    public BuildingCookMixin(final IColony c, final BlockPos l)
    {
        super(c, l);
    }

    // 预定餐桌
    public void preorderTable(int customerId){
        customersOnTheWay.add(customerId);
        processingCustomers.remove(customerId);
        customerQueue.remove(customerId);
    }

    // 客人到店
    public void reached(int customerId){
        customersOnTheWay.remove(customerId);
    }

    // 查询用餐人数
    public int getCustomerCount(){
        return customersOnTheWay.size() + this.checkSize();
    }

    // 查询预定人员
    public List<Integer> getOrders(){
        return customersOnTheWay.stream().toList();
    }

    // 分片获取 Customers
    public List<Integer> getCustomers(int maxCount) {
        List<Integer> assigned = new ArrayList<>();
        while (assigned.size() < maxCount && !customerQueue.isEmpty()) {
            Integer customerId = customerQueue.poll();
            if (customerId == null) {
                continue;
            }
            ICitizenData citizenData = getColony().getCitizenManager().getCivilian(customerId);
            if (citizenData == null || citizenData.getEntity().isEmpty()
                    || !isInBuilding(citizenData.getEntity().get().blockPosition())
                    || citizenData.getSaturation() >= CitizenConstants.FULL_SATURATION) {
                processingCustomers.remove(customerId);
                continue;
            }
            if (processingCustomers.add(customerId)) {
                assigned.add(customerId);
            }
        }
        return assigned;
    }

    // 释放 Customer
    public void releaseProcessingCustomer(int customerId, boolean requeue) {
        processingCustomers.remove(customerId);
        if (requeue && !customerQueue.contains(customerId)) {
            customerQueue.offer(customerId);
        }
    }

    // 完全删除 Customer
    public void deleteCustomer(int customerId) {
        processingCustomers.remove(customerId);
        customerQueue.remove(customerId);
    }

    // 顾客注册
    public void tryRegisterCustomer(int citizenId) {
        reached(citizenId);
        if (!customerQueue.contains(citizenId) && !processingCustomers.contains(citizenId)) {
            customerQueue.offer(citizenId);
        }
    }

    // 顾客监测
    public boolean checkCustomerRegistry(int citizenId){
        return customerQueue.contains(citizenId) || processingCustomers.contains(citizenId);
    }

    // 获取当前顾客数量
    public int checkSize(){
        return customerQueue.size() + processingCustomers.size();
    }

    // 获取当前可提供食物的玩家
    public boolean getPlayCanServe(UUID playerID) {
        return !playerServing.containsKey(playerID) && !playerFed.containsKey(playerID);
    }

    // 增加玩家服务冷却
    public void setPlayerServed(UUID playerID) {
        playerServing.remove(playerID);
        playerFed.put(playerID, 5); // 5 -> 2500 tick, about 2 minutes.
    }

    // 添加正在接收服务的玩家
    public void setPlayerServing(Queue<Player> playerList) {
        for(Player player : playerList) {
            playerServing.put(player.getUUID(), 5);
        }
    }

    // 删除正在接收服务的玩家
    public void removePlayerServing(UUID playerID) {
        playerServing.remove(playerID);
    }
    public void removePlayerListServing(Queue<Player> playerList) {
        for(Player player : playerList) {
            playerServing.remove(player.getUUID());
        }
    }

    @Override
    public void onColonyTick(final IColony colony) {
        super.onColonyTick(colony);
        for (Iterator<Map.Entry<UUID, Integer>> it = playerFed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) {
                it.remove();
            } else {
                entry.setValue(cooldown);
            }
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = playerServing.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) {
                it.remove();
            } else {
                entry.setValue(cooldown);
            }
        }

        // Clean up customersOnTheWay who already arrived or cancelled
        for (Integer customerId : new ArrayList<>(customersOnTheWay)) {
            ICitizenData citizenData = colony.getCitizenManager().getCivilian(customerId);
            if (citizenData == null || citizenData.getEntity().isEmpty()) {
                customersOnTheWay.remove(customerId);
            } else if (citizenData.getEntity().get() instanceof EntityCitizen citizen) {
                IState state = citizen.getCitizenAI().getState();
                if (state != NewEntityAIEat.NewEatingState.CHECK_FOOD && state != NewEntityAIEat.NewEatingState.GO_TO_RESTAURANT) {
                    customersOnTheWay.remove(customerId);
                }
            }
        }

        // If an assigned cook left work (e.g. to eat or sleep), release their unserved customers back to the queue
        final List<NewEntityAIWorkCook> activeCooks = new ArrayList<>();
        for (ICitizenData workerData : getAllAssignedCitizen()) {
            if (workerData.getEntity().isPresent() && workerData.getEntity().get() instanceof EntityCitizen workerEntity) {
                if (workerEntity.getCitizenJobHandler().getWorkAI() instanceof NewEntityAIWorkCook cookAI) {
                    if (workerEntity.getCitizenAI().getState() != CitizenAIState.WORK) {
                        cookAI.releaseUnservedCustomers();
                    } else {
                        activeCooks.add(cookAI);
                    }
                }
            }
        }

        // Clean up stale entries in customerQueue
        customerQueue.removeIf(customerId -> {
            ICitizenData citizenData = colony.getCitizenManager().getCivilian(customerId);
            return citizenData == null || citizenData.getEntity().isEmpty()
                    || !isInBuilding(citizenData.getEntity().get().blockPosition())
                    || citizenData.getSaturation() >= CitizenConstants.FULL_SATURATION;
        });

        // Rescue any orphaned customers in processingCustomers
        for (Integer customerId : new ArrayList<>(processingCustomers)) {
            ICitizenData citizenData = colony.getCitizenManager().getCivilian(customerId);
            if (citizenData == null || citizenData.getEntity().isEmpty()
                    || !isInBuilding(citizenData.getEntity().get().blockPosition())
                    || citizenData.getSaturation() >= CitizenConstants.FULL_SATURATION) {
                processingCustomers.remove(customerId);
                continue;
            }
            boolean beingServed = false;
            for (NewEntityAIWorkCook cookAI : activeCooks) {
                if (cookAI.isServingCustomer(customerId)) {
                    beingServed = true;
                    break;
                }
            }
            if (!beingServed) {
                releaseProcessingCustomer(customerId, true);
            }
        }
    }
}