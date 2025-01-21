package qupath.lib.gui.measure;

import qupath.lib.lazy.objects.PathObjectLazyValues;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Measurements that only extract metadata from objects.
 * @return
 */
public class MeasurementListValueBuilder implements PathObjectLazyValueBuilder {

    @Override
    public List<LazyValue<PathObject, ?>> getValues(PathObjectListWrapper wrapper) {
        var list = new ArrayList<LazyValue<PathObject, ?>>();
        wrapper.getFeatureNames()
                .stream()
                .map(PathObjectLazyValues::createMeasurementListMeasurement)
                .forEach(list::add);
        return list;
    }

}
