package com.example.srmremoter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class BluetoothDeviceListAdapter extends ArrayAdapter<BluetoothDeviceListAdapter.Item> {
    private static final class ViewHolder {
        final TextView name;
        final TextView address;
        final TextView details;

        ViewHolder(View view) {
            name = view.findViewById(R.id.deviceItemName);
            address = view.findViewById(R.id.deviceItemAddress);
            details = view.findViewById(R.id.deviceItemDetails);
        }
    }

    static final class Item {
        final BluetoothDevice device;
        String name;
        int rssi;
        boolean hasRssi;
        final boolean spp;
        boolean bonded;

        Item(BluetoothDevice device, String name, boolean spp) {
            this.device = device;
            this.name = name;
            this.spp = spp;
        }
    }

    BluetoothDeviceListAdapter(Context context, List<Item> items) {
        super(context, 0, items);
    }

    @NonNull
    @Override
    @SuppressLint("MissingPermission")
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        ViewHolder holder;
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_bluetooth_device, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        Item item = getItem(position);
        if (item == null) {
            return view;
        }
        holder.name.setText(item.name);
        holder.address.setText(item.device.getAddress());
        String rssi = item.hasRssi ? Integer.toString(item.rssi) : getContext().getString(R.string.rssi_unknown);
        String type = item.spp
                ? getContext().getString(R.string.device_type_spp)
                : getContext().getString(R.string.device_type_ffe1);
        String details = item.spp
                ? getContext().getString(R.string.device_details_spp, type,
                        getContext().getString(item.bonded
                                ? R.string.device_bonded : R.string.device_not_bonded), rssi)
                : getContext().getString(R.string.device_details_ble, type, rssi);
        holder.details.setText(details);
        return view;
    }
}
