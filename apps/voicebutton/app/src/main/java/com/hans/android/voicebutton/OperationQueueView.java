package com.hans.android.voicebutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.hans.android.common_ui.AndroidUi;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stable viewport with recycled row views; complete identity is available by tap or accessibility. */
final class OperationQueueView extends ListView {
    private final List<Row> pending = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();
    private final Rows adapter = new Rows();
    private View measuringRow;

    // ListView handles item clicks and accessibility; this listener only arbitrates nested scrolling.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    OperationQueueView(Context context) {
        super(context);
        setAdapter(adapter);
        setDivider(null);
        setVisibility(GONE);
        setScrollbarFadingEnabled(false);
        setNestedScrollingEnabled(true);
        setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            new MaterialAlertDialogBuilder(context).setTitle(row.name.isEmpty() ? "Transfer status" : row.name)
                    .setMessage(row.state + (row.message ? "" : "\n"
                            + (row.indeterminate ? "Progress not yet measurable" : ((row.progress + 5) / 10) + "%")))
                    .setPositiveButton("Close", null).show();
        });
        setOnTouchListener((view, event) -> {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(
                    event.getActionMasked() != android.view.MotionEvent.ACTION_UP
                    && event.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL
                    && (canScrollVertically(1) || canScrollVertically(-1)));
            return false;
        });
    }

    void beginUpdate() { pending.clear(); }
    void add(String name, String state, int progress, boolean indeterminate, boolean failed) {
        pending.add(new Row(name, state, progress, indeterminate, false,
                failed ? AndroidUi.ORANGE : AndroidUi.INK));
    }
    void message(String text, int color) { pending.add(new Row("", text, 0, false, true, color)); }
    void commitUpdate() {
        if (!rows.equals(pending)) {
            rows = new ArrayList<>(pending);
            setVisibility(rows.isEmpty()?GONE:VISIBLE);
            adapter.notifyDataSetChanged();requestLayout();
        }
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        if(rows.isEmpty()){setMeasuredDimension(MeasureSpec.getSize(widthSpec),0);return;}
        int width=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        measuringRow=adapter.getView(0,measuringRow,this);
        measuringRow.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        int height=Math.max(AndroidUi.dp(getContext(),48),measuringRow.getMeasuredHeight());
        if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED)height=Math.min(height,MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Holder holder;
            if (recycled == null) {
                LinearLayout box = new LinearLayout(getContext());
                box.setOrientation(LinearLayout.VERTICAL);
                box.setMinimumHeight(AndroidUi.dp(getContext(), 48));
                holder = new Holder(box);
                box.setTag(holder);
                recycled = box;
            } else holder = (Holder) recycled.getTag();
            Row row = rows.get(position);
            holder.name.setVisibility(row.message ? GONE : VISIBLE);
            holder.name.setText(row.name);
            holder.name.setTextColor(row.color);
            holder.state.setText((row.message||row.indeterminate?"":((row.progress+5)/10)+"% · ")+row.state);
            holder.state.setTextColor(row.color);
            holder.progress.setVisibility(row.message ? GONE : VISIBLE);
            holder.progress.setIndeterminate(row.indeterminate);
            if (!row.indeterminate) holder.progress.setProgress(row.progress);
            recycled.setContentDescription((row.name.isEmpty() ? "" : row.name + ". ") + row.state
                    + (row.message ? "" : row.indeterminate ? ". Progress unknown" : ". " + ((row.progress + 5) / 10) + " percent")
                    + ". Tap for full details.");
            return recycled;
        }
    }
    private final class Holder {
        final TextView name, state;
        final ProgressBar progress;
        Holder(LinearLayout box) {
            name = AndroidUi.small(getContext(), "");
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            state = AndroidUi.small(getContext(), "");
            state.setSingleLine(false);
            state.setEllipsize(null);
            progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            box.addView(name);
            box.addView(state);
            box.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(getContext(), 8)));
            box.setPadding(0, 0, 0, AndroidUi.dp(getContext(), 2));
        }
    }
    private static final class Row {
        final String name, state;
        final int progress, color;
        final boolean indeterminate, message;
        Row(String name, String state, int progress, boolean indeterminate, boolean message, int color) {
            this.name = name == null ? "" : name;
            this.state = state == null ? "" : state;
            this.progress = Math.max(0, Math.min(1000, progress));
            this.indeterminate = indeterminate;
            this.message = message;
            this.color = color;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Row)) return false;
            Row r = (Row) other;
            return name.equals(r.name) && state.equals(r.state) && progress == r.progress
                    && color == r.color && indeterminate == r.indeterminate && message == r.message;
        }
        @Override public int hashCode() { return Objects.hash(name, state, progress, color, indeterminate, message); }
    }
}
