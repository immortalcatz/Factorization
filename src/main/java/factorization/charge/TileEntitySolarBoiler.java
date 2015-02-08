package factorization.charge;

import factorization.shared.*;
import factorization.util.DataUtil;
import factorization.util.FluidUtil;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import factorization.api.Coord;
import factorization.api.IMeterInfo;
import factorization.api.IReflectionTarget;
import factorization.common.BlockIcons;
import factorization.common.FactoryType;
import factorization.common.FzConfig;

public class TileEntitySolarBoiler extends TileEntityCommon implements IReflectionTarget, IFluidHandler, IMeterInfo {
    public static Fluid steam;
    public static FluidStack water_stack = null;
    public static FluidStack steam_stack = null;
    
    public static void setupSteam() {
        if (water_stack == null) {
            water_stack = new FluidStack(FluidRegistry.WATER, 0);
            steam_stack = FluidRegistry.getFluidStack("steam", 0);
            steam = steam_stack.getFluid();
        }
    }
    
    FluidTank waterTank = new FluidTank(/*this, */ water_stack.copy(), 1000*8);
    FluidTank steamTank = new FluidTank(/*this, */ steam_stack.copy(), 1000*8);
    int reflector_count = 0;
    
    public TileEntitySolarBoiler() {
        waterTank.getFluid().amount = 0;
        steamTank.getFluid().amount = 0;
    }

    @Override
    public FactoryType getFactoryType() {
        return FactoryType.SOLARBOILER;
    }
    
    @Override
    public IIcon getIcon(ForgeDirection dir) {
        switch (dir) {
        case UP: return BlockIcons.boiler_top;
        default: return BlockIcons.boiler_side;
        }
    }

    @Override
    public BlockClass getBlockClass() {
        return BlockClass.Machine;
    }
    
    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        DataUtil.writeTank(tag, waterTank, "water");
        DataUtil.writeTank(tag, steamTank, "steam");
    }
    
    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        DataUtil.readTank(tag, waterTank, "water");
        DataUtil.readTank(tag, steamTank, "steam");
        sanitize();
    }
    
    private FluidTank getTank(ForgeDirection from) {
        if (from == ForgeDirection.UP) {
            return steamTank;
        }
        return waterTank;
    }
    
    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (resource.isFluidEqual(water_stack)) {
            return waterTank.fill(resource, doFill);
        } else if (resource.isFluidEqual(steam_stack)) {
            return steamTank.fill(resource, doFill);
        } else {
            return 0;
        }
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return getTank(from).drain(maxDrain, doDrain);
    }
    
    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return false;
    }
    
    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        if (from == ForgeDirection.UP) {
            return false;
        }
        return fluid == null || fluid.getID() == water_stack.fluidID;
    }
    
    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (from != ForgeDirection.UP) return null; 
        FluidTank tank = getTank(from);
        if (resource == null || tank.getFluid().fluidID != resource.fluidID) {
            return null;
        }
        return tank.drain(tank.getCapacity(), doDrain);
    }
    
    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        return new FluidTankInfo[] {getTank(from).getInfo()};
    }

    //MAIN LOGIC
    @Override
    public void addReflector(int strength) {
        reflector_count = Math.max(0, reflector_count + strength);
    }
    
    int getWater() {
        return waterTank.getFluid().amount;
    }
    
    int getSteam() {
        return steamTank.getFluid().amount;
    }
    
    int getHeat() {
        return Math.max(reflector_count - 3, 0);
    }
    
    void sanitize() {
        if (waterTank.getFluid() == null) {
            waterTank.setFluid(water_stack.copy());
        }
        if (steamTank.getFluid() == null) {
            steamTank.setFluid(steam_stack.copy());
        }
    }
    
    @Override
    public void updateEntity() {
        if (worldObj.isRemote) {
            return;
        }
        sanitize();
        FluidStack water = waterTank.getFluid();
        FluidStack steam = steamTank.getFluid();
        Coord here = getCoord();
        long seed = here.seed() + worldObj.getTotalWorldTime();
        if (steam.amount*2 > steamTank.getCapacity() || seed % 20 == 0) {
            //Send steam upwards
            Coord above = here.add(0, 1, 0);
            IFluidHandler tc = above.getTE(IFluidHandler.class);
            if (tc != null) {
                FluidStack sending_steam = steam.copy();
                sending_steam.amount = Math.min(sending_steam.amount, 1000);
                steam.amount -= tc.fill(ForgeDirection.DOWN, steam.copy(), true);
                steam.amount = Math.max(0, steam.amount);
            }
        }
        boolean random = seed % 40 == 0;
        if (water.amount <= 1000 || (random && water.amount < waterTank.getCapacity() - 1000)) {
            //pull water from below
            Coord below = here.add(0, -1, 0);
            IFluidHandler tc = below.getTE(IFluidHandler.class);
            boolean water_below = (below.is(Blocks.flowing_water) || below.is(Blocks.water));
            water_below &= !here.isPowered();
            if (water_below && FzConfig.boilers_suck_water) {
                if (below.getMd() == 0) {
                    below.setAir();
                    water.amount += 1000;
                    water.amount = Math.min(water.amount, waterTank.getCapacity());
                }
            } else if (tc != null) {
                ForgeDirection dir = ForgeDirection.UP;
                if (below.getTE(TileEntitySolarBoiler.class) != null) {
                    dir = ForgeDirection.DOWN;
                }
                int free = Math.max(0, waterTank.getCapacity() - water.amount);
                free = Math.min(1000/10, free);
                FluidStack avail = tc.drain(dir, free, false);
                if (avail != null && avail.isFluidEqual(water_stack)) {
                    water.amount += tc.drain(dir, free, true).amount;
                }
            }
            return;
        }
        
        //try boiling
        int time_scale = 1;
//		if (seed % time_scale != 0) {
//			return;
//		}
        if (getHeat() <= 0) {
            return; //nothing to heat
        }
        applyHeat(getHeat()*time_scale);
    }
    
    public void applyHeat(int heat) {
        sanitize();
        FluidStack water = waterTank.getFluid();
        FluidStack steam = steamTank.getFluid();
        if (steam.amount >= steamTank.getCapacity()) {
            return; //no room for more steam
        }
        int toBoil = Math.min(heat, water.amount);
        toBoil = Math.min(steamTank.getCapacity() - steam.amount, toBoil);
        int water_to_steam = 160; /* CovertJaguar gives 1:160 as the water:steam ratio */;
        int water_to_remove = Math.max(toBoil/water_to_steam, 1);
        if (water_to_remove > water.amount) {
            return;
        }
        water.amount -= water_to_remove;
        steam.amount += (int)(toBoil*FzConfig.steam_output_adjust);
    }
    
    @Override
    protected void onRemove() {
        super.onRemove();
        Coord here = getCoord();
        FluidUtil.spill(here, waterTank.getFluid());
        FluidUtil.spill(here, steamTank.getFluid());
    }
    
    @Override
    public String getInfo() {
        sanitize();
        float w = waterTank.getFluid().amount*16/(float)waterTank.getCapacity();
        float s = steamTank.getFluid().amount*16/(float)steamTank.getCapacity();
        return "Power: " + reflector_count
                + "\nSteam: " + String.format("%.1f", s)
                + "\nWater: " + String.format("%.1f", w);
    }
}
