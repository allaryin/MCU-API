package org.mcupdater.model.curse.feed;

import java.util.ArrayList;
import java.util.List;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

public class Feed {

    @SerializedName("timestamp")
    @Expose
    private Integer timestamp;
    @SerializedName("data")
    @Expose
    private List<Datum> data = new ArrayList<Datum>();

    public Integer getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Integer timestamp) {
        this.timestamp = timestamp;
    }

    public List<Datum> getData() {
        return data;
    }

    public void setData(List<Datum> data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toStringExclude(this, new String[] {""});
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(timestamp).append(data).toHashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof Feed) == false) {
            return false;
        }
        Feed rhs = ((Feed) other);
        return new EqualsBuilder().append(timestamp, rhs.timestamp).append(data, rhs.data).isEquals();
    }

}
