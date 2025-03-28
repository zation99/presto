/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.type.ArrayType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.SqlScalarFunction;
import com.facebook.presto.spi.function.ComplexTypeFunctionDescriptor;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.function.LambdaArgumentDescriptor;
import com.facebook.presto.spi.function.LambdaDescriptor;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.spi.function.SqlFunctionVisibility;
import com.facebook.presto.sql.gen.lambda.BinaryFunctionInterface;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static com.facebook.presto.common.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.common.type.TypeUtils.readNativeValue;
import static com.facebook.presto.common.type.TypeUtils.writeNativeValue;
import static com.facebook.presto.metadata.BuiltInTypeAndFunctionNamespaceManager.JAVA_BUILTIN_NAMESPACE;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.ArgumentProperty.functionTypeArgumentProperty;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.ArgumentProperty.valueTypeArgumentProperty;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.NullConvention.RETURN_NULL_ON_NULL;
import static com.facebook.presto.spi.function.Signature.typeVariable;
import static com.facebook.presto.spi.function.SqlFunctionVisibility.PUBLIC;
import static com.facebook.presto.util.Reflection.methodHandle;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static java.lang.Math.max;

public final class ZipWithFunction
        extends SqlScalarFunction
{
    public static final ZipWithFunction ZIP_WITH_FUNCTION = new ZipWithFunction();
    private static final MethodHandle METHOD_HANDLE = methodHandle(ZipWithFunction.class, "zipWith", Type.class, Type.class, ArrayType.class, Block.class, Block.class, BinaryFunctionInterface.class);

    private final ComplexTypeFunctionDescriptor descriptor;

    private ZipWithFunction()
    {
        super(new Signature(
                QualifiedObjectName.valueOf(JAVA_BUILTIN_NAMESPACE, "zip_with"),
                FunctionKind.SCALAR,
                ImmutableList.of(typeVariable("T"), typeVariable("U"), typeVariable("R")),
                ImmutableList.of(),
                parseTypeSignature("array(R)"),
                ImmutableList.of(parseTypeSignature("array(T)"), parseTypeSignature("array(U)"), parseTypeSignature("function(T,U,R)")),
                false));
        descriptor = new ComplexTypeFunctionDescriptor(
                true,
                ImmutableList.of(new LambdaDescriptor(2, ImmutableMap.of(
                        0, new LambdaArgumentDescriptor(0, ComplexTypeFunctionDescriptor::prependAllSubscripts),
                        1, new LambdaArgumentDescriptor(1, ComplexTypeFunctionDescriptor::prependAllSubscripts)))),
                Optional.of(ImmutableSet.of(0, 1)),
                Optional.of(ComplexTypeFunctionDescriptor::clearRequiredSubfields),
                getSignature());
    }

    @Override
    public SqlFunctionVisibility getVisibility()
    {
        return PUBLIC;
    }

    @Override
    public boolean isDeterministic()
    {
        return false;
    }

    @Override
    public String getDescription()
    {
        return "merge two arrays, element-wise, into a single array using the lambda function";
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        Type leftElementType = boundVariables.getTypeVariable("T");
        Type rightElementType = boundVariables.getTypeVariable("U");
        Type outputElementType = boundVariables.getTypeVariable("R");
        ArrayType outputArrayType = new ArrayType(outputElementType);
        return new BuiltInScalarFunctionImplementation(
                false,
                ImmutableList.of(
                        valueTypeArgumentProperty(RETURN_NULL_ON_NULL),
                        valueTypeArgumentProperty(RETURN_NULL_ON_NULL),
                        functionTypeArgumentProperty(BinaryFunctionInterface.class)),
                METHOD_HANDLE.bindTo(leftElementType).bindTo(rightElementType).bindTo(outputArrayType));
    }

    @Override
    public ComplexTypeFunctionDescriptor getComplexTypeFunctionDescriptor()
    {
        return descriptor;
    }

    public static Block zipWith(
            Type leftElementType,
            Type rightElementType,
            ArrayType outputArrayType,
            Block leftBlock,
            Block rightBlock,
            BinaryFunctionInterface function)
    {
        Type outputElementType = outputArrayType.getElementType();
        int leftPositionCount = leftBlock.getPositionCount();
        int rightPositionCount = rightBlock.getPositionCount();
        int outputPositionCount = max(leftPositionCount, rightPositionCount);

        BlockBuilder arrayBlockBuilder = outputArrayType.createBlockBuilder(null, max(leftPositionCount, rightPositionCount));
        BlockBuilder blockBuilder = arrayBlockBuilder.beginBlockEntry();

        for (int position = 0; position < outputPositionCount; position++) {
            Object left = position < leftPositionCount ? readNativeValue(leftElementType, leftBlock, position) : null;
            Object right = position < rightPositionCount ? readNativeValue(rightElementType, rightBlock, position) : null;
            Object output;
            try {
                output = function.apply(left, right);
            }
            catch (Throwable throwable) {
                throwIfUnchecked(throwable);
                throw new RuntimeException(throwable);
            }
            writeNativeValue(outputElementType, blockBuilder, output);
        }

        arrayBlockBuilder.closeEntry();
        return outputArrayType.getObject(arrayBlockBuilder, arrayBlockBuilder.getPositionCount() - 1);
    }
}
